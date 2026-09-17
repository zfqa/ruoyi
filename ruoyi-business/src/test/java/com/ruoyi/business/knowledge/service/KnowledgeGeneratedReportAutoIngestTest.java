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
import com.ruoyi.business.report.domain.AiReport;
import com.ruoyi.business.report.service.IAiReportService;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class KnowledgeGeneratedReportAutoIngestTest
{
    @Test
    void createsDedicatedReportSourceAndCompletesAutoIngest()
    {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        IAiReportService reportService = mock(IAiReportService.class);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        KnowledgeFileStorage storage = KnowledgeFileStorage.forTests("target/knowledge-auto-report-test");

        AiReport report = new AiReport();
        report.setId(91L); report.setImportTaskId(45L); report.setTaskName("竞争社洞察自动报�?);
        report.setReportType("competitive_insight_v1"); report.setStatus("2");
        report.setReportContent("{\"computed_metrics\":{\"shipment\":{\"metric_id\":\"shipment.total\"," +
            "\"value\":321}},\"executive_summary\":\"Tianma出货保持增长。\"}");

        AtomicReference<KnowledgeBase> sourceRef = new AtomicReference<>();
        AtomicReference<KnowledgeVersion> versionRef = new AtomicReference<>();
        AtomicReference<KnowledgeIngestTask> taskRef = new AtomicReference<>();
        List<KnowledgeChunk> chunks = new ArrayList<>();
        when(reportService.selectAiReportById(91L)).thenReturn(report);
        when(mapper.selectKnowledgeBaseBySourceCode("AUTO-REPORT-TASK-45")).thenReturn(null);
        doAnswer(invocation -> {
            KnowledgeBase source = invocation.getArgument(0); source.setId(12L); sourceRef.set(source); return 1;
        }).when(mapper).insertKnowledgeBase(any(KnowledgeBase.class));
        when(mapper.selectKnowledgeBaseById(12L)).thenAnswer(invocation -> sourceRef.get());
        when(mapper.selectVersionByHash(any(), any())).thenReturn(null);
        doAnswer(invocation -> {
            KnowledgeVersion version = invocation.getArgument(0); version.setId(22L); versionRef.set(version); return 1;
        }).when(mapper).insertVersion(any(KnowledgeVersion.class));
        doAnswer(invocation -> {
            KnowledgeIngestTask task = invocation.getArgument(0); task.setId(32L); taskRef.set(task); return 1;
        }).when(mapper).insertIngestTask(any(KnowledgeIngestTask.class));
        when(mapper.selectVersionById(22L)).thenAnswer(invocation -> versionRef.get());
        when(mapper.selectIngestTaskById(32L)).thenAnswer(invocation -> taskRef.get());
        when(mapper.selectIngestTaskByVersionId(22L)).thenAnswer(invocation -> taskRef.get());
        doAnswer(invocation -> {
            sourceRef.get().setCurrentVersionId(invocation.getArgument(1));
            return 1;
        }).when(mapper).promoteCurrentVersionIfNewer(any(), any(), any());
        doAnswer(invocation -> { chunks.add(invocation.getArgument(0)); return 1; })
            .when(mapper).insertChunk(any(KnowledgeChunk.class));
        doAnswer(invocation -> { ((Runnable) invocation.getArgument(0)).run(); return null; })
            .when(executor).execute(any(Runnable.class));

        KnowledgeIngestService service = new KnowledgeIngestService(mapper, storage, reportService, executor, "");
        KnowledgeIngestTask task = service.submitGeneratedReport(91L, "tester");

        assertEquals(32L, task.getId());
        assertEquals("2", task.getStatus());
        assertEquals("AUTO-REPORT-TASK-45", sourceRef.get().getSourceCode());
        assertEquals("REPORT", sourceRef.get().getSourceType());
        assertEquals(22L, sourceRef.get().getCurrentVersionId());
        assertFalse(chunks.isEmpty());
        assertTrue(chunks.stream().anyMatch(chunk -> "shipment.total".equals(chunk.getMetricId())
            && chunk.getContent().contains("321")));
        assertTrue(chunks.stream().anyMatch(chunk -> chunk.getContent().contains("Tianma出货保持增长")));
    }
}
