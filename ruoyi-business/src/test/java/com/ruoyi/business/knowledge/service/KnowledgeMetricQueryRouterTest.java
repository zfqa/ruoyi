package com.ruoyi.business.knowledge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.ruoyi.business.knowledge.domain.KnowledgeChunk;
import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeMetricQueryRouterTest
{
    @Test
    void ranksStructuredMetricByCompanyTechnologyPeriodAndMeasure()
    {
        KnowledgeChunk expected = metric(1L, "tianma.l tps.shipment.y25q1q3",
            "Tianma LTPS Y25 Q1-Q3 Shipment 1200 Kpcs");
        KnowledgeChunk unrelated = metric(2L, "boe.asi.displayarea.y24",
            "BOE a-Si Y24 Display area");

        List<KnowledgeChunk> result = new KnowledgeMetricQueryRouter().rank(
            "Tianma 2025前三季度LTPS出货量是多少", List.of(unrelated, expected), 5);

        assertEquals(1L, result.get(0).getId());
        assertTrue(result.get(0).getScore() > 0);
    }

    @Test
    void ignoresNonMetricQuestions()
    {
        assertTrue(new KnowledgeMetricQueryRouter().rank("请介绍这份报告",
            List.of(metric(1L, "tianma.shipment", "Tianma Shipment")), 5).isEmpty());
    }

    @Test
    void buildsStableDatabasePrefilterTerms()
    {
        assertEquals(List.of("tianma", "ltps", "shipment"),
            new KnowledgeMetricQueryRouter().candidateTerms("天马2025年前三季度LTPS出货量和同比"));
    }

    @Test
    void prefersAtomicMetricOverBroadDataset()
    {
        KnowledgeChunk dataset = metric(10L, "dataset.tianma_product",
            "Tianma LTPS Shipment Y25Q1-Q3 1200 yoy 20%");
        KnowledgeChunk atomic = metric(1L, "tianma.technology.ltps.shipment",
            "Tianma LTPS Shipment periods Y25Q1-Q3 1200 yoy 20%");
        List<KnowledgeChunk> result = new KnowledgeMetricQueryRouter().rank(
            "Tianma 2025前三季度LTPS出货量和同比", List.of(dataset, atomic), 5);
        assertEquals("tianma.technology.ltps.shipment", result.get(0).getMetricId());
    }

    @Test
    void coalescesLegacyOverlappingJsonFragments()
    {
        KnowledgeChunk first = metric(1L, "tianma.technology.ltps.shipment", "{\"periods\":{\"Y25Q1-Q3\":10323},\"yoy");
        first.setVersionId(9L); first.setChunkNo(4);
        KnowledgeChunk second = metric(2L, "tianma.technology.ltps.shipment", "\"yoy\":0.680176}");
        second.setVersionId(9L); second.setChunkNo(5);
        List<KnowledgeChunk> result = new KnowledgeMetricQueryRouter().coalesceFragments(List.of(second, first));
        assertEquals(1, result.size());
        assertTrue(result.get(0).getContent().contains("10323"));
        assertTrue(result.get(0).getContent().endsWith("0.680176}"));
        assertEquals(List.of(first, second), result.get(0).getSourceFragments());
        assertEquals("{\"periods\":{\"Y25Q1-Q3\":10323},\"yoy", first.getContent());
    }

    @Test
    void dropsDisplayShipmentWhenQuestionNamesVehicleSales()
    {
        KnowledgeChunk hud = metric(1L, "market.hud.shipment.y25f", "HUD Y25F shipment 1801");
        KnowledgeChunk byd = metric(2L, "oem.byd.sales.y26", "比亚迪 2026 销量 440293");

        List<KnowledgeChunk> result = new KnowledgeMetricQueryRouter().rank(
            "比亚迪2026年销量", List.of(hud, byd), 5);

        assertEquals(1, result.size());
        assertEquals(2L, result.get(0).getId());
    }

    @Test
    void salesStaffIsNotTreatedAsVehicleVolume()
    {
        KnowledgeChunk volume = metric(2L, "oem.byd.sales.y25", "比亚迪 2025 销量 440293");
        assertTrue(new KnowledgeMetricQueryRouter().rank("比亚迪销售人员有多少", List.of(volume), 5).isEmpty());
        assertTrue(KnowledgeMetricQueryRouter.isVehicleSalesVolumeQuestion("比亚迪2026年销量"));
        org.junit.jupiter.api.Assertions.assertFalse(
            KnowledgeMetricQueryRouter.isVehicleSalesVolumeQuestion("比亚迪销售人员有多少"));
        org.junit.jupiter.api.Assertions.assertFalse(
            KnowledgeMetricQueryRouter.isVehicleSalesVolumeQuestion("销售费用是多少"));
    }

    private KnowledgeChunk metric(Long id, String metricId, String content)
    {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(id); chunk.setMetricId(metricId); chunk.setContent(content);
        return chunk;
    }
}
