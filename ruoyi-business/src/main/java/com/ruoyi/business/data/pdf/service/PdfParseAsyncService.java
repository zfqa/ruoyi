package com.ruoyi.business.data.pdf.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Date;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.business.agent.client.AgentServiceClient;
import com.ruoyi.business.agent.client.AgentServiceClientException;
import com.ruoyi.business.data.pdf.domain.PdfParse;
import com.ruoyi.business.data.pdf.mapper.PdfParseMapper;
import com.ruoyi.business.knowledge.service.LlmRuntimeConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

/** 从已保存的若依文件重新构造上传对象，在后台完成耗时的 Python 解析。 */
@Service
public class PdfParseAsyncService
{
    private static final Logger LOG = LoggerFactory.getLogger(PdfParseAsyncService.class);
    private final PdfParseMapper pdfParseMapper;
    private final AgentServiceClient agentServiceClient;
    private final ThreadPoolTaskExecutor executor;
    private final DocumentPrivateStorage documentStorage;
    private final LlmRuntimeConfiguration llmConfiguration;
    private final Set<Long> runningTasks = ConcurrentHashMap.newKeySet();

    public PdfParseAsyncService(PdfParseMapper pdfParseMapper, AgentServiceClient agentServiceClient,
            @Qualifier("pdfParseTaskExecutor") ThreadPoolTaskExecutor executor, DocumentPrivateStorage documentStorage,
            LlmRuntimeConfiguration llmConfiguration)
    {
        this.pdfParseMapper = pdfParseMapper;
        this.agentServiceClient = agentServiceClient;
        this.executor = executor;
        this.documentStorage = documentStorage;
        this.llmConfiguration = llmConfiguration;
    }

    /** 投递本身不等待 Python；队列满时直接将已创建任务标记失败。 */
    public boolean submit(Long taskId)
    {
        if (!reserve(taskId)) return false;
        return submitReserved(taskId);
    }

    /** 先占用任务，再做数据库 CAS，避免失败状态刚写入时被并发重复提交。 */
    public boolean reserve(Long taskId)
    {
        return runningTasks.add(taskId);
    }

    public boolean submitReserved(Long taskId)
    {
        if (!runningTasks.contains(taskId)) return false;
        try { executor.execute(() -> process(taskId)); }
        catch (RuntimeException e)
        {
            runningTasks.remove(taskId);
            LOG.warn("PDF parse task rejected taskId={}", taskId, e);
            markFailed(taskId, "解析任务队列已满，请稍后重试");
            return false;
        }
        return true;
    }

    public void releaseReservation(Long taskId)
    {
        runningTasks.remove(taskId);
    }

    /** 包可见，供离线测试直接验证后台工作结果。 */
    void process(Long taskId)
    {
        try
        {
            PdfParse task = pdfParseMapper.selectPdfParseById(taskId);
            if (task == null || !"1".equals(task.getStatus())) return;
            Path savedFile = resolveSavedFile(task.getStoredFilePath());
            if (!Files.isRegularFile(savedFile)) throw new IOException("已保存的原文件不存在");
            String lower = task.getOriginalFileName() == null ? "" : task.getOriginalFileName().toLowerCase();
            String contentType = lower.endsWith(".pptx")
                ? "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                : "application/pdf";
            JSONObject result = agentServiceClient.parseDocument(savedFile, task.getOriginalFileName(), contentType);
            if (!"success".equalsIgnoreCase(result.getString("status")) || !result.getBooleanValue("supported"))
                throw new IOException(firstNonBlank(result.getString("message"), result.getString("reason"), "文档解析失败"));
            if (result.getBooleanValue("indexed") || result.getBooleanValue("index_to_kb")
                || result.getBooleanValue("persist_review") || result.getIntValue("review_count") > 0)
                throw new IOException("解析服务违反统一知识库约束，已拒绝结果");
            markSucceeded(taskId, result);
        }
        catch (Exception e)
        {
            LOG.warn("PDF parse task failed taskId={} errorType={}", taskId, e.getClass().getSimpleName(), e);
            markFailed(taskId, safeFailureMessage(e));
        }
        finally { runningTasks.remove(taskId); }
    }

    private void markSucceeded(Long taskId, JSONObject result)
    {
        PdfParse update = update(taskId, "2");
        update.setResultJson(result.toJSONString());
        JSONArray entities = result.getJSONArray("entities");
        update.setEntityCount(entities == null ? 0 : entities.size());
        update.setLlmModel(firstNonBlank(result.getString("llm_model"), llmConfiguration.getModel()));
        update.setErrorMessage("");
        pdfParseMapper.updatePdfParseResult(update);
    }

    private void markFailed(Long taskId, String message)
    {
        PdfParse update = update(taskId, "3");
        update.setErrorMessage(message);
        pdfParseMapper.updatePdfParseResult(update);
    }

    private PdfParse update(Long taskId, String status)
    {
        PdfParse update = new PdfParse();
        update.setId(taskId);
        update.setStatus(status);
        update.setCompletedTime(new Date());
        update.setUpdateBy("system");
        return update;
    }

    private Path resolveSavedFile(String storedFilePath) throws IOException
    {
        return documentStorage.resolveForRead(storedFilePath);
    }

    private String safeFailureMessage(Exception exception)
    {
        if (exception instanceof AgentServiceClientException) return exception.getMessage();
        if (exception instanceof IOException && exception.getMessage() != null) return exception.getMessage();
        return "文档解析服务处理失败，请稍后重试";
    }

    private String firstNonBlank(String... values)
    {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "文档解析失败";
    }
}
