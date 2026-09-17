package com.ruoyi.business.knowledge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ruoyi.business.knowledge.domain.KnowledgeBase;
import com.ruoyi.business.knowledge.domain.KnowledgeChunk;
import com.ruoyi.business.knowledge.domain.KnowledgeIngestTask;
import com.ruoyi.business.knowledge.domain.KnowledgeVersion;
import com.ruoyi.business.knowledge.mapper.KnowledgeBaseMapper;
import com.ruoyi.business.report.service.IAiReportService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class KnowledgePolicyIngestIntegrationTest
{
    @Test
    void ingestsPolicyAsIndependentTraceableSource() throws Exception
    {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        KnowledgeBase source = new KnowledgeBase();
        source.setId(9L); source.setSourceName("汽车产业政策"); source.setSourceType("POLICY"); source.setEnabled("1");
        AtomicReference<KnowledgeVersion> versionRef = new AtomicReference<>();
        AtomicReference<KnowledgeIngestTask> taskRef = new AtomicReference<>();
        List<KnowledgeChunk> chunks = new ArrayList<>();
        when(mapper.selectKnowledgeBaseById(9L)).thenReturn(source);
        when(mapper.selectVersionByHash(any(), any())).thenReturn(null);
        doAnswer(invocation -> { KnowledgeVersion value=invocation.getArgument(0); value.setId(31L); versionRef.set(value); return 1; }).when(mapper).insertVersion(any());
        doAnswer(invocation -> { KnowledgeIngestTask value=invocation.getArgument(0); value.setId(32L); taskRef.set(value); return 1; }).when(mapper).insertIngestTask(any());
        when(mapper.selectVersionById(31L)).thenAnswer(invocation -> versionRef.get());
        when(mapper.selectIngestTaskById(32L)).thenAnswer(invocation -> taskRef.get());
        when(mapper.selectIngestTaskByVersionId(31L)).thenAnswer(invocation -> taskRef.get());
        doAnswer(invocation -> { KnowledgeChunk value=invocation.getArgument(0); value.setId((long)chunks.size()+1); chunks.add(value); return 1; }).when(mapper).insertChunk(any());
        doAnswer(invocation -> { ((Runnable)invocation.getArgument(0)).run(); return null; }).when(executor).execute(any(Runnable.class));

        KnowledgeIngestService service = new KnowledgeIngestService(mapper,
            KnowledgeFileStorage.forTests("target/knowledge-policy-ingest"), mock(IAiReportService.class), executor, "");
        KnowledgeIngestTask task = service.submitPolicy(9L, "policy-v1",
            "https://www.gov.cn/zhengce/example.html", "关于促进汽车消费和制造业高质量发展的通知",
            "国家支持汽车以旧换新，鼓励新能源汽车消费，并支持汽车制造企业技术改造和扩大有效供给�?,
            "国务�?, "2025-01-15", "国家", "tester");

        assertEquals("2", task.getStatus());
        assertEquals("https://www.gov.cn/zhengce/example.html", versionRef.get().getSourceUrl());
        assertEquals("2025-01-15T00:00", versionRef.get().getPublishedTime().toString());
        assertTrue(versionRef.get().getStoredPath().replace('\\', '/').contains("/policy/"));
        assertTrue(versionRef.get().getStoredPath().endsWith(".txt"));
        assertTrue(chunks.stream().allMatch(c -> "https://www.gov.cn/zhengce/example.html".equals(c.getSourceUrl())));
        assertTrue(chunks.stream().anyMatch(c -> c.getContent().contains("发布机关：国务院")
            && c.getEvidenceJson().contains("\"kind\":\"POLICY\"")
            && c.getEvidenceJson().contains("2025-01-15")));
    }
}
