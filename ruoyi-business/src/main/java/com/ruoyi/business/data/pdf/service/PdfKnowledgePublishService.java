package com.ruoyi.business.data.pdf.service;

import java.io.IOException;
import java.nio.file.Path;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.business.data.pdf.domain.PdfParse;
import com.ruoyi.business.data.pdf.mapper.PdfParseMapper;
import com.ruoyi.business.knowledge.service.KnowledgeIngestService;
import org.springframework.stereotype.Service;

/** Publish an already saved parsing snapshot into the unified MySQL knowledge base. */
@Service
public class PdfKnowledgePublishService
{
    private final PdfParseMapper pdfParseMapper;
    private final KnowledgeIngestService knowledgeIngestService;
    private final DocumentPrivateStorage documentStorage;

    public PdfKnowledgePublishService(PdfParseMapper pdfParseMapper,
        KnowledgeIngestService knowledgeIngestService, DocumentPrivateStorage documentStorage)
    {
        this.pdfParseMapper = pdfParseMapper;
        this.knowledgeIngestService = knowledgeIngestService;
        this.documentStorage = documentStorage;
    }

    public Object publish(Long taskId, String username)
    {
        PdfParse task = pdfParseMapper.selectPdfParseById(taskId);
        if (task == null) throw new IllegalArgumentException("文档解析任务不存在");
        if (!"2".equals(task.getStatus())) throw new IllegalStateException("仅允许发布解析成功的文档任务");
        if (task.getResultJson() == null || task.getResultJson().isBlank())
            throw new IllegalStateException("文档解析结果为空，无法发布知识库");
        try
        {
            JSONObject parsed = JSONObject.parseObject(task.getResultJson());
            Path original = documentStorage.resolveForRead(task.getStoredFilePath());
            return knowledgeIngestService.submitParsedDocument(taskId, task.getTaskName(),
                task.getOriginalFileName(), original, parsed, username == null ? "" : username);
        }
        catch (IOException e)
        {
            throw new IllegalStateException(e.getMessage(), e);
        }
        catch (RuntimeException e)
        {
            if (e instanceof IllegalArgumentException || e instanceof IllegalStateException) throw e;
            throw new IllegalStateException("文档解析结果格式无效，无法发布知识库", e);
        }
    }
}
