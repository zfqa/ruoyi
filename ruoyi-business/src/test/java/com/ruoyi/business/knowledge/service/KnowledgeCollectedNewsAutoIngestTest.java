package com.ruoyi.business.knowledge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import com.ruoyi.business.knowledge.domain.KnowledgeBase;
import com.ruoyi.business.knowledge.domain.KnowledgeChunk;
import com.ruoyi.business.knowledge.domain.KnowledgeIngestTask;
import com.ruoyi.business.knowledge.domain.KnowledgeVersion;
import com.ruoyi.business.knowledge.mapper.KnowledgeBaseMapper;
import com.ruoyi.business.report.service.IAiReportService;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class KnowledgeCollectedNewsAutoIngestTest
{
    @Test
    void createsArticleSourceWithUrlAndEvidenceInUnifiedKnowledgeBase() throws Exception
    {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        KnowledgeFileStorage storage = new KnowledgeFileStorage("target/knowledge-collected-news-test");
        AtomicReference<KnowledgeBase> sourceRef = new AtomicReference<>();
        AtomicReference<KnowledgeVersion> versionRef = new AtomicReference<>();
        AtomicReference<KnowledgeIngestTask> taskRef = new AtomicReference<>();
        List<KnowledgeChunk> chunks = new ArrayList<>();

        when(mapper.selectKnowledgeBaseBySourceCode("NEWS-ARTICLE-27")).thenReturn(null);
        doAnswer(invocation -> {
            KnowledgeBase source = invocation.getArgument(0);
            source.setId(12L);
            sourceRef.set(source);
            return 1;
        }).when(mapper).insertKnowledgeBase(any(KnowledgeBase.class));
        when(mapper.selectKnowledgeBaseById(12L)).thenAnswer(invocation -> sourceRef.get());
        when(mapper.selectVersionByHash(any(), any())).thenReturn(null);
        doAnswer(invocation -> {
            KnowledgeVersion version = invocation.getArgument(0);
            version.setId(22L);
            versionRef.set(version);
            return 1;
        }).when(mapper).insertVersion(any(KnowledgeVersion.class));
        doAnswer(invocation -> {
            KnowledgeIngestTask task = invocation.getArgument(0);
            task.setId(32L);
            taskRef.set(task);
            return 1;
        }).when(mapper).insertIngestTask(any(KnowledgeIngestTask.class));
        when(mapper.selectVersionById(22L)).thenAnswer(invocation -> versionRef.get());
        when(mapper.selectIngestTaskById(32L)).thenAnswer(invocation -> taskRef.get());
        doAnswer(invocation -> {
            sourceRef.get().setCurrentVersionId(invocation.getArgument(1));
            return 1;
        }).when(mapper).promoteCurrentVersionIfNewer(any(), any(), any());
        doAnswer(invocation -> {
            chunks.add(invocation.getArgument(0));
            return 1;
        }).when(mapper).insertChunk(any(KnowledgeChunk.class));
        doAnswer(invocation -> {
            ((Runnable) invocation.getArgument(0)).run();
            return null;
        }).when(executor).execute(any(Runnable.class));

        KnowledgeIngestService service = new KnowledgeIngestService(mapper, storage,
            mock(IAiReportService.class), executor, "");
        KnowledgeIngestTask task = service.submitCollectedNews(27L, "示例站点", "example.com",
            "新能源汽车政策推动市场增长", "政策提出扩大新能源汽车消费，并支持产业链技术升级和产能建设。",
            "https://example.com/news/27", "2026-09-12T08:30:00", "2026-09-13T09:00:00",
            storage.sha256("政策提出扩大新能源汽车消费，并支持产业链技术升级和产能建设。"), "crawler");

        assertEquals("2", task.getStatus());
        assertEquals("NEWS-ARTICLE-27", sourceRef.get().getSourceCode());
        assertEquals("新能源汽车政策推动市场增长", sourceRef.get().getSourceName());
        assertEquals("NEWS", sourceRef.get().getSourceType());
        assertEquals("https://example.com/news/27", versionRef.get().getSourceUrl());
        assertEquals(22L, sourceRef.get().getCurrentVersionId());
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().allMatch(chunk -> "https://example.com/news/27".equals(chunk.getSourceUrl())));
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.getEvidenceJson().contains("NEWS")
            && chunk.getEvidenceJson().contains("示例站点")
            && chunk.getEvidenceJson().contains("2026-09-12")));
    }
}
