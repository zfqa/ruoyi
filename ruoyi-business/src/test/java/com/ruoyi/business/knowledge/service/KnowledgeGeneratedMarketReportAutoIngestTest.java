package com.ruoyi.business.knowledge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.nio.file.Files;
import java.nio.file.Path;
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

class KnowledgeGeneratedMarketReportAutoIngestTest
{
    @Test
    void storesOfficeExportAndPublishesSearchableGeneratedReport() throws Exception
    {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        IAiReportService reportService = mock(IAiReportService.class);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        KnowledgeFileStorage storage = new KnowledgeFileStorage("target/knowledge-auto-market-report-test");
        AtomicReference<KnowledgeBase> sourceRef = new AtomicReference<>();
        AtomicReference<KnowledgeVersion> versionRef = new AtomicReference<>();
        AtomicReference<KnowledgeIngestTask> taskRef = new AtomicReference<>();
        List<KnowledgeChunk> chunks = new ArrayList<>();

        when(mapper.selectKnowledgeBaseBySourceCode(any())).thenAnswer(invocation -> sourceRef.get());
        when(mapper.selectVersionByHash(any(), any())).thenAnswer(invocation -> versionRef.get());
        doAnswer(invocation -> {
            KnowledgeBase source = invocation.getArgument(0); source.setId(101L); sourceRef.set(source); return 1;
        }).when(mapper).insertKnowledgeBase(any(KnowledgeBase.class));
        doAnswer(invocation -> {
            KnowledgeVersion version = invocation.getArgument(0); version.setId(201L); versionRef.set(version); return 1;
        }).when(mapper).insertVersion(any(KnowledgeVersion.class));
        doAnswer(invocation -> {
            KnowledgeIngestTask task = invocation.getArgument(0); task.setId(301L); taskRef.set(task); return 1;
        }).when(mapper).insertIngestTask(any(KnowledgeIngestTask.class));
        when(mapper.selectVersionById(201L)).thenAnswer(invocation -> versionRef.get());
        when(mapper.selectIngestTaskById(301L)).thenAnswer(invocation -> taskRef.get());
        when(mapper.selectIngestTaskByVersionId(201L)).thenAnswer(invocation -> taskRef.get());
        doAnswer(invocation -> { chunks.add(invocation.getArgument(0)); return 1; })
            .when(mapper).insertChunk(any(KnowledgeChunk.class));
        doAnswer(invocation -> {
            sourceRef.get().setCurrentVersionId(invocation.getArgument(1)); return 1;
        }).when(mapper).promoteCurrentVersionIfNewer(any(), any(), any());
        doAnswer(invocation -> { ((Runnable) invocation.getArgument(0)).run(); return null; })
            .when(executor).execute(any(Runnable.class));

        KnowledgeIngestService service = new KnowledgeIngestService(mapper, storage, reportService, executor, "");
        byte[] officeBytes = new byte[] {'P', 'K', 3, 4, 1, 2, 3};
        String report = "{\"executive_summary\":\"新能源汽车份额持续提升\","
            + "\"metrics\":{\"sales\":440293,\"growth\":12.5}}";
        KnowledgeIngestTask task = service.submitGeneratedMarketReport("dataset-demo", "xlsx",
            "整车市场周报.xlsx", officeBytes, report, "period=latest|export=21.0", "tester");

        assertEquals(301L, task.getId());
        assertEquals("2", task.getStatus());
        assertEquals("REPORT", sourceRef.get().getSourceType());
        assertEquals("知识问答、报告分析及来源追溯", sourceRef.get().getAllowedPurpose());
        assertTrue(sourceRef.get().getSourceCode().startsWith("AUTO-MARKET-REPORT-"));
        assertEquals("整车市场周报.xlsx", sourceRef.get().getSourceName());
        assertEquals(201L, sourceRef.get().getCurrentVersionId());
        assertTrue(Files.isRegularFile(Path.of(versionRef.get().getStoredPath())));
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.getContent().contains("新能源汽车份额持续提升")));
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.getContent().contains("440293")
            && "market.metrics".equals(chunk.getMetricId())));

        KnowledgeIngestTask duplicate = service.submitGeneratedMarketReport("dataset-demo", "xlsx",
            "整车市场周报.xlsx", officeBytes, report, "period=latest|export=21.0", "tester");
        assertSame(task, duplicate);
        verify(mapper, times(1)).insertVersion(any(KnowledgeVersion.class));
        verify(mapper, times(1)).insertIngestTask(any(KnowledgeIngestTask.class));
    }
}
