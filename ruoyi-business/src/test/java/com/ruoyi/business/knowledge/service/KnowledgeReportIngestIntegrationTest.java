package com.ruoyi.business.knowledge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

/** 验证结构化报告的指标证据真正写入知识切片并切换为当前版本。 */
class KnowledgeReportIngestIntegrationTest
{
    @Test
    void ingestsReportMetricIdAndEvidenceIntoTraceableChunk()
    {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        IAiReportService reportService = mock(IAiReportService.class);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        KnowledgeFileStorage storage = new KnowledgeFileStorage("target/knowledge-report-test");

        KnowledgeBase source = new KnowledgeBase();
        source.setId(7L); source.setSourceName("竞争社洞察结构化结果"); source.setSourceType("REPORT");
        source.setEnabled("1"); source.setStatus("0");
        AiReport report = new AiReport();
        report.setId(88L); report.setTaskName("竞争社洞察报告");
        report.setStatus("2");
        report.setReportContent("{\"computed_metrics\":{\"market\":{\"metric_id\":\"market.shipment.y25q1_q3\","
            + "\"values\":{\"2024\":100,\"2025\":120}}},\"evidence\":[{\"metric_id\":\"market.shipment.y25q1_q3\","
            + "\"evidence\":{\"2025\":{\"sheet\":\"Shipment\",\"cells\":[\"A10\",\"A11\"]}}}]}" );

        AtomicReference<KnowledgeVersion> versionRef = new AtomicReference<>();
        AtomicReference<KnowledgeIngestTask> taskRef = new AtomicReference<>();
        List<KnowledgeChunk> chunks = new ArrayList<>();
        when(mapper.selectKnowledgeBaseById(7L)).thenReturn(source);
        when(mapper.selectVersionByHash(any(), any())).thenReturn(null);
        when(reportService.selectAiReportById(88L)).thenReturn(report);
        doAnswer(invocation -> {
            KnowledgeVersion version = invocation.getArgument(0);
            version.setId(22L); versionRef.set(version); return 1;
        }).when(mapper).insertVersion(any(KnowledgeVersion.class));
        doAnswer(invocation -> {
            KnowledgeIngestTask task = invocation.getArgument(0);
            task.setId(33L); taskRef.set(task); return 1;
        }).when(mapper).insertIngestTask(any(KnowledgeIngestTask.class));
        when(mapper.selectVersionById(22L)).thenAnswer(invocation -> versionRef.get());
        when(mapper.selectIngestTaskById(33L)).thenAnswer(invocation -> taskRef.get());
        when(mapper.selectIngestTaskByVersionId(22L)).thenAnswer(invocation -> taskRef.get());
        doAnswer(invocation -> { chunks.add(invocation.getArgument(0)); return 1; })
            .when(mapper).insertChunk(any(KnowledgeChunk.class));
        doAnswer(invocation -> { ((Runnable) invocation.getArgument(0)).run(); return null; })
            .when(executor).execute(any(Runnable.class));

        KnowledgeIngestService service = new KnowledgeIngestService(mapper, storage, reportService, executor, "");
        KnowledgeIngestTask submitted = service.submitReport(7L, "v-report-1", 88L, "tester");

        assertEquals(33L, submitted.getId());
        assertEquals("2", submitted.getStatus());
        assertEquals(22L, source.getCurrentVersionId());
        assertEquals("2", source.getStatus());
        assertFalse(chunks.isEmpty());
        KnowledgeChunk metricChunk = chunks.stream()
            .filter(chunk -> "market.shipment.y25q1_q3".equals(chunk.getMetricId())).findFirst().orElseThrow();
        assertEquals(88L, metricChunk.getReportId());
        assertNotNull(metricChunk.getEvidenceJson());
        assertTrue(metricChunk.getEvidenceJson().contains("Shipment"));
        assertTrue(metricChunk.getEvidenceJson().contains("A10"));
        assertTrue(metricChunk.getContent().contains("120"));
    }
}
