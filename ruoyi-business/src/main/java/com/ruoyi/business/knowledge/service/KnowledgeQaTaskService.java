package com.ruoyi.business.knowledge.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

/**
 * POC知识问答异步任务。长耗时LLM调用不占用浏览器请求连接，前端轮询真实阶段与检索日志。
 */
@Service
public class KnowledgeQaTaskService
{
    private static final long RETENTION_SECONDS = 6 * 60 * 60;
    private static final int MAX_RETAINED_TASKS = 200;
    private final KnowledgeQaService qaService;
    private final ThreadPoolTaskExecutor executor;
    private final Map<String, QaTask> tasks = new ConcurrentHashMap<>();

    public KnowledgeQaTaskService(KnowledgeQaService qaService,
        @Qualifier("knowledgeQaTaskExecutor") ThreadPoolTaskExecutor executor)
    {
        this.qaService = qaService;
        this.executor = executor;
    }

    public Map<String, Object> submit(String question, String sourceType, boolean includeNews,
        List<Long> roleIds, boolean admin, String owner)
    {
        if (question == null || question.trim().length() < 2) throw new IllegalArgumentException("问题至少2个字符");
        cleanup();
        String taskId = UUID.randomUUID().toString().replace("-", "");
        QaTask task = new QaTask(taskId, owner, qaService.previewQueryPlan(question, sourceType, includeNews));
        tasks.put(taskId, task);
        try
        {
            executor.execute(() -> run(task, question.trim(), sourceType, includeNews,
                roleIds == null ? List.of() : List.copyOf(roleIds), admin));
        }
        catch (RuntimeException e)
        {
            tasks.remove(taskId);
            throw new IllegalStateException("知识问答任务队列已满，请稍后重试");
        }
        return task.snapshot(false);
    }

    public Map<String, Object> get(String taskId, String owner, boolean admin)
    {
        QaTask task = tasks.get(taskId);
        if (task == null) throw new IllegalArgumentException("问答任务不存在或已过期");
        if (!admin && !task.owner.equals(owner)) throw new IllegalArgumentException("无权查看该问答任务");
        return task.snapshot(true);
    }

    private void run(QaTask task, String question, String sourceType, boolean includeNews,
        List<Long> roleIds, boolean admin)
    {
        task.start();
        try
        {
            Map<String, Object> result = qaService.ask(question, sourceType, roleIds, admin, includeNews,
                task::progress);
            task.complete(result);
        }
        catch (Exception e)
        {
            task.fail(safeMessage(e));
        }
    }

    private String safeMessage(Exception error)
    {
        String message = error.getMessage();
        if (message == null || message.isBlank()) message = error.getClass().getSimpleName();
        return message.length() <= 300 ? message : message.substring(0, 300) + "...";
    }

    private void cleanup()
    {
        Instant cutoff = Instant.now().minusSeconds(RETENTION_SECONDS);
        tasks.entrySet().removeIf(entry -> entry.getValue().finishedAt != null
            && entry.getValue().finishedAt.isBefore(cutoff));
        if (tasks.size() <= MAX_RETAINED_TASKS) return;
        tasks.values().stream().filter(task -> task.finishedAt != null)
            .sorted((left, right) -> left.finishedAt.compareTo(right.finishedAt))
            .limit(tasks.size() - MAX_RETAINED_TASKS)
            .forEach(task -> tasks.remove(task.taskId));
    }

    private static final class QaTask
    {
        private final String taskId;
        private final String owner;
        private final Instant createdAt = Instant.now();
        private volatile Instant startedAt;
        private volatile Instant finishedAt;
        private volatile String status = "QUEUED";
        private volatile int progress;
        private volatile String currentStage = "等待执行";
        private volatile String errorMessage = "";
        private volatile List<Map<String, Object>> queryPlan;
        private volatile List<Map<String, Object>> retrievalLogs = List.of();
        private volatile Map<String, Object> result;

        QaTask(String taskId, String owner, List<Map<String, Object>> queryPlan)
        {
            this.taskId = taskId;
            this.owner = owner == null ? "" : owner;
            this.queryPlan = copyRows(queryPlan);
        }

        synchronized void start()
        {
            status = "RUNNING";
            progress = 2;
            currentStage = "任务已启动";
            startedAt = Instant.now();
        }

        synchronized void progress(int value, String stage, List<Map<String, Object>> logs)
        {
            status = "RUNNING";
            progress = Math.max(progress, Math.min(value, 99));
            currentStage = stage;
            retrievalLogs = copyRows(logs);
        }

        @SuppressWarnings("unchecked")
        synchronized void complete(Map<String, Object> value)
        {
            result = value == null ? Map.of() : new LinkedHashMap<>(value);
            Object finalPlan = result.get("queryPlan");
            if (finalPlan instanceof List<?>) queryPlan = copyRows((List<Map<String, Object>>) finalPlan);
            Object finalLogs = result.get("retrievalLogs");
            if (finalLogs instanceof List<?>) retrievalLogs = copyRows((List<Map<String, Object>>) finalLogs);
            status = "SUCCESS";
            progress = 100;
            currentStage = "问答与来源图谱生成完成";
            finishedAt = Instant.now();
        }

        synchronized void fail(String message)
        {
            status = "FAILED";
            currentStage = "处理失败";
            errorMessage = message;
            finishedAt = Instant.now();
        }

        synchronized Map<String, Object> snapshot(boolean includeResult)
        {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("taskId", taskId);
            value.put("status", status);
            value.put("progress", progress);
            value.put("currentStage", currentStage);
            value.put("errorMessage", errorMessage);
            value.put("queryPlan", copyRows(queryPlan));
            value.put("retrievalLogs", copyRows(retrievalLogs));
            value.put("createdAt", createdAt.toString());
            value.put("startedAt", startedAt == null ? null : startedAt.toString());
            value.put("finishedAt", finishedAt == null ? null : finishedAt.toString());
            if (includeResult && result != null) value.put("result", new LinkedHashMap<>(result));
            return value;
        }

        private static List<Map<String, Object>> copyRows(List<Map<String, Object>> rows)
        {
            if (rows == null) return List.of();
            List<Map<String, Object>> copy = new ArrayList<>();
            for (Map<String, Object> row : rows) copy.add(new LinkedHashMap<>(row));
            return copy;
        }
    }
}
