package com.ruoyi.business.knowledge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import com.ruoyi.business.report.domain.AiReport;
import org.junit.jupiter.api.Test;

class KnowledgeReportProjectionServiceTest
{
    @Test
    void projectsMetricsResidualDatasetsConclusionsAndEvidenceWithoutWholeReportCopy()
    {
        AiReport report = new AiReport();
        report.setId(88L);
        report.setReportType("competitive_insight_v1");
        report.setGenerationMode("python_metrics_llm_narrative");
        report.setReportContent("""
            {
              "computed_metrics": {
                "market": {
                  "metric_id": "market.shipment.y25q1_q3",
                  "values": {"2024": 100, "2025": 120},
                  "evidence": {"rows": ["pivot-cache:r10"]}
                },
                "bubble_chart": {"sizes": [8.8, 10.3], "shipments": [300, 520]}
              },
              "evidence": [{
                "metric_id": "market.shipment.y25q1_q3",
                "evidence": {"2025": {"file": "tracker.xlsx", "sheet": "Shipment", "cells": ["A10", "A11"]}}
              }],
              "executive_summary": "2025年前三季度市场出货量同比增长20%。",
              "insights": {"drivers": ["LTPS大尺寸产品拉动增长。"]},
              "narrative_sources": [{
                "conclusion": "2025年前三季度市场出货量同比增长20%。",
                "citation_label": "S1",
                "source_file": "tracker.xlsx",
                "metric_ids": ["market.shipment.y25q1_q3"],
                "evidence": [{"sheet": "Shipment", "cells": ["A10", "A11"]}]
              }],
              "quality": {"status": "complete"}
            }
            """);

        List<KnowledgeReportProjectionService.ReportKnowledge> units =
            new KnowledgeReportProjectionService().project(report);

        List<KnowledgeReportProjectionService.ReportKnowledge> metrics = units.stream()
            .filter(item -> "METRIC".equals(item.kind())).toList();
        assertEquals(1, metrics.size());
        assertEquals("market.shipment.y25q1_q3", metrics.get(0).metricId());
        assertTrue(metrics.get(0).content().contains("120"));
        assertFalse(metrics.get(0).content().contains("pivot-cache:r10"));
        assertTrue(metrics.get(0).evidenceJson().contains("Shipment"));
        assertTrue(metrics.get(0).evidenceJson().contains("A10"));

        KnowledgeReportProjectionService.ReportKnowledge dataset = units.stream()
            .filter(item -> "DATASET".equals(item.kind())).findFirst().orElseThrow();
        assertTrue(dataset.content().contains("10.3"));
        assertFalse(dataset.content().contains("market.shipment.y25q1_q3"));

        List<KnowledgeReportProjectionService.ReportKnowledge> conclusions = units.stream()
            .filter(item -> "CONCLUSION".equals(item.kind())).toList();
        assertTrue(conclusions.stream().anyMatch(item -> item.content().contains("同比增长20%")
            && item.evidenceJson().contains("METRIC_EVIDENCE") && item.evidenceJson().contains("A11")));
        assertTrue(conclusions.stream().anyMatch(item -> item.content().contains("LTPS大尺寸产品拉动增长")));
        assertTrue(units.stream().anyMatch(item -> "METADATA".equals(item.kind())
            && item.content().contains("complete")));
        assertFalse(units.stream().anyMatch(item -> report.getReportContent().equals(item.content())));
    }
}
