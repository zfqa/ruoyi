package com.ruoyi.business.knowledge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;
import static org.mockito.ArgumentMatchers.eq;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.business.knowledge.mapper.KnowledgeBaseMapper;

class KnowledgeQaTaskServiceTest
{
    @Test
    @SuppressWarnings("unchecked")
    void exposesRealtimeProgressAndProtectsTaskOwner() throws Exception
    {
        KnowledgeQaService qaService = mock(KnowledgeQaService.class);
        List<Map<String, Object>> plan = List.of(
            Map.of("order", 1, "name", "问题解析", "status", "DONE"),
            Map.of("order", 2, "name", "数据证据检索", "status", "PENDING"),
            Map.of("order", 3, "name", "引用核验", "status", "PENDING"));
        when(qaService.previewQueryPlan(anyString(), anyString(), anyBoolean())).thenReturn(plan);
        doAnswer(invocation -> {
            KnowledgeQaService.QaProgressListener listener = invocation.getArgument(6);
            listener.onProgress(35, "已完成多源检索", List.of(
                Map.of("type", "SEARCH", "action", "混合证据检索", "status", "SUCCESS")));
            Thread.sleep(80);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("answer", "测试回答[S1]"); result.put("queryPlan", plan);
            result.put("retrievalLogs", List.of(Map.of("type", "VERIFY", "status", "SUCCESS")));
            result.put("citations", List.of()); result.put("graph", Map.of("nodes", List.of(), "links", List.of()));
            return result;
        }).when(qaService).ask(anyString(), anyString(), anyList(), anyBoolean(), anyBoolean(), anyBoolean(),
            any(KnowledgeQaService.QaProgressListener.class));

        ThreadPoolTaskExecutor executor = executor();
        try
        {
            KnowledgeQaTaskService service = new KnowledgeQaTaskService(qaService, executor);
            Map<String, Object> submitted = service.submit("小鹏近期销量及新闻原因", "", true,
                List.of(2L), false, "alice");
            String taskId = String.valueOf(submitted.get("taskId"));
            Map<String, Object> task = submitted;
            for (int i = 0; i < 40 && !"SUCCESS".equals(task.get("status")); i++)
            {
                Thread.sleep(25);
                task = service.get(taskId, "alice", false);
            }
            assertEquals("SUCCESS", task.get("status"));
            assertEquals(100, task.get("progress"));
            assertTrue(((List<Map<String, Object>>) task.get("queryPlan")).size() >= 2);
            assertTrue(((List<Map<String, Object>>) task.get("retrievalLogs")).size() >= 1);
            assertTrue(task.containsKey("result"));
            assertThrows(IllegalArgumentException.class, () -> service.get(taskId, "bob", false));
            assertEquals("SUCCESS", service.get(taskId, "admin", true).get("status"));
        }
        finally
        {
            executor.shutdown();
        }
    }

    @Test
    void persistsCompletedClaimsAndRestoresTaskAfterMemoryLoss() throws Exception
    {
        KnowledgeQaService qaService = mock(KnowledgeQaService.class);
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        when(qaService.previewQueryPlan(anyString(), anyString(), anyBoolean())).thenReturn(List.of());
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("citationLabel", "S1"); evidence.put("chunkId", 88L); evidence.put("sourceName", "终稿");
        evidence.put("pageStart", 14); evidence.put("pageEnd", 14); evidence.put("startOffset", 20);
        evidence.put("endOffset", 42); evidence.put("evidenceSnippet", "原文证据");
        Map<String, Object> claim = new LinkedHashMap<>();
        claim.put("claimText", "天马同比增长20%。"); claim.put("verified", true); claim.put("evidences", List.of(evidence));
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("answer", "天马同比增长20%。[S1]"); answer.put("claims", List.of(claim));
        when(qaService.ask(anyString(), anyString(), anyList(), anyBoolean(), anyBoolean(), anyBoolean(),
            any(KnowledgeQaService.QaProgressListener.class))).thenReturn(answer);

        ThreadPoolTaskExecutor executor = executor();
        try
        {
            KnowledgeQaTaskService service = new KnowledgeQaTaskService(qaService, executor);
            service.setMapper(mapper);
            String taskId = String.valueOf(service.submit("天马增长多少", "REPORT", true, List.of(), false, "alice").get("taskId"));
            Map<String, Object> snapshot = Map.of();
            for (int i = 0; i < 40; i++)
            {
                Thread.sleep(25);
                snapshot = service.get(taskId, "alice", false);
                if ("SUCCESS".equals(snapshot.get("status"))) break;
            }
            assertEquals("SUCCESS", snapshot.get("status"));
            verify(mapper).insertQaClaim(taskId, 1, "天马同比增长20%。", true);
            verify(mapper).insertQaCitation(taskId, 1, "S1", 88L, "终稿", 14, 14, 20, 42, "原文证据");

            KnowledgeQaTaskService restarted = new KnowledgeQaTaskService(qaService, executor);
            restarted.setMapper(mapper);
            when(mapper.selectQaSession(taskId)).thenReturn(Map.of("owner", "alice", "snapshotJson", JSON.toJSONString(snapshot)));
            assertEquals("SUCCESS", restarted.get(taskId, "alice", false).get("status"));
            assertThrows(IllegalArgumentException.class, () -> restarted.get(taskId, "bob", false));
        }
        finally { executor.shutdown(); }
    }

    private ThreadPoolTaskExecutor executor()
    {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1); executor.setMaxPoolSize(1); executor.setQueueCapacity(2);
        executor.setThreadNamePrefix("qa-test-"); executor.initialize();
        return executor;
    }
}
