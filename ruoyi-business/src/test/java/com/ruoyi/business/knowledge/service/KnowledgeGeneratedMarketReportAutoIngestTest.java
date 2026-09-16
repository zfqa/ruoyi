package com.ruoyi.business.knowledge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.ruoyi.business.report.domain.AiReport;
import com.ruoyi.business.report.service.IAiReportService;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class KnowledgeGeneratedMarketReportAutoIngestTest
{
    @Test
    void storesOfficeExportAndPublishesAiReportPlusKnowledge() throws Exception
    {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        IAiReportService reportService = mock(IAiReportService.class);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        KnowledgeFileStorage storage = new KnowledgeFileStorage("target/knowledge-auto-market-report-test");
        AtomicReference<KnowledgeBase> sourceRef = new AtomicReference<>();
        AtomicReference<KnowledgeVersion> versionRef = new AtomicReference<>();
        AtomicReference<KnowledgeIngestTask> taskRef = new AtomicReference<>();
        AtomicReference<AiReport> aiReportRef = new AtomicReference<>();
        List<KnowledgeChunk> chunks = new ArrayList<>();

        when(reportService.selectLatestAiReportByTaskName(anyString())).thenAnswer(invocation -> aiReportRef.get());
        doAnswer(invocation -> {
            AiReport report = invocation.getArgument(0); report.setId(401L); aiReportRef.set(report); return 1;
        }).when(reportService).insertAiReport(any(AiReport.class));
        doAnswer(invocation -> {
            AiReport report = invocation.getArgument(0); aiReportRef.set(report); return 1;
        }).when(reportService).updateAiReport(any(AiReport.class));

        when(mapper.selectKnowledgeBaseBySourceCode(any())).thenAnswer(invocation -> sourceRef.get());
        when(mapper.selectVersionByHash(any(), any())).thenAnswer(invocation -> versionRef.get());
        doAnswer(invocation -> {
            KnowledgeBase source = invocation.getArgument(0); source.setId(101L); sourceRef.set(source); return 1;
        }).when(mapper).insertKnowledgeBase(any(KnowledgeBase.class));
        doAnswer(invocation -> {
            KnowledgeVersion version = invocation.getArgument(0); version.setId(201L); versionRef.set(version); return 1;
        }).when(mapper).insertVersion(any(KnowledgeVersion.class));
        doAnswer(invocation -> {
            KnowledgeVersion version = invocation.getArgument(0); versionRef.set(version); return 1;
        }).when(mapper).updateVersion(any(KnowledgeVersion.class));
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
        KnowledgeIngestService.VehicleMarketPublishResult published = service.publishVehicleMarketReport(
            "dataset-demo", report, "tester", "整车市场周报.xlsx", officeBytes, "xlsx",
            "period=latest|export=21.0");

        assertNotNull(published.report());
        assertEquals(401L, published.report().getId());
        assertEquals("vehicle_market_v21", published.report().getReportType());
        assertEquals("整车市场周报 - dataset-demo", published.report().getTaskName());
        assertEquals(301L, published.knowledgeTask().getId());
        assertEquals("2", published.knowledgeTask().getStatus());
        assertEquals("REPORT", sourceRef.get().getSourceType());
        assertTrue(sourceRef.get().getSourceCode().startsWith("AUTO-REPORT-VEHICLE-"));
        assertEquals("整车市场周报 - dataset-demo", sourceRef.get().getSourceName());
        assertEquals(201L, sourceRef.get().getCurrentVersionId());
        assertTrue(Files.isRegularFile(Path.of(versionRef.get().getStoredPath())));
        assertEquals("/business/knowledge/versions/201/file", versionRef.get().getSourceUrl());
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.getContent().contains("新能源汽车份额持续提升")));
        assertTrue(chunks.stream().anyMatch(chunk -> Long.valueOf(401L).equals(chunk.getReportId())));
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.getContent().contains("440293")
            && "market.metrics".equals(chunk.getMetricId())));

        KnowledgeIngestTask duplicate = service.submitGeneratedMarketReport("dataset-demo", "xlsx",
            "整车市场周报.xlsx", officeBytes, report, "period=latest|export=21.0", "tester");
        assertSame(published.knowledgeTask(), duplicate);
        verify(mapper, times(1)).insertVersion(any(KnowledgeVersion.class));
        verify(mapper, times(1)).insertIngestTask(any(KnowledgeIngestTask.class));
        verify(reportService, times(1)).insertAiReport(any(AiReport.class));
    }
}
