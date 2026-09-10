package com.ruoyi.business.knowledge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import java.util.Map;
import com.ruoyi.business.knowledge.domain.KnowledgeChunk;
import org.junit.jupiter.api.Test;

class KnowledgeCitationValidatorTest
{
    private final KnowledgeCitationValidator validator = new KnowledgeCitationValidator();

    @Test
    void validatesEveryClaimAndLocatesOriginalEvidence()
    {
        KnowledgeChunk first = chunk(1L, "Tianma 2025年前三季度LTPS出货量为1200 Kpcs，同比增长20%。", 8);
        KnowledgeChunk second = chunk(2L, "AUO 2025年前三季度LTPS出货量为900 Kpcs，同比下降5%。", 14);

        KnowledgeCitationValidator.ValidationResult result = validator.validate(
            "出货表现：\nTianma 2025年前三季度LTPS出货量为1200 Kpcs，同比增长20%。[S1]\n"
                + "AUO同期LTPS出货量为900 Kpcs，同比下降5%。[S2]",
            List.of(first, second));

        assertEquals(List.of(1, 2), result.getCitedIndexes());
        assertEquals(2, result.getClaims().size());
        Map<String, Object> evidence = result.getEvidenceForSource(1).get(0);
        assertEquals(8, evidence.get("pageStart"));
        assertEquals("测试原始文件.pdf", evidence.get("originalName"));
        assertTrue(String.valueOf(evidence.get("evidenceSnippet")).contains("1200 Kpcs"));
        assertTrue((Double) evidence.get("matchScore") > 0.0);
    }

    @Test
    void rejectsAnyFactualSentenceWithoutCitation()
    {
        KnowledgeChunk source = chunk(1L, "Tianma出货量增长。AUO出货量下降。", 3);
        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> validator.validate("Tianma出货量增长。[S1] AUO出货量下降。", List.of(source)));
        assertTrue(error.getMessage().contains("未引用的事实句"));
    }

    @Test
    void rejectsInvalidCitationNumber()
    {
        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> validator.validate("Tianma出货量增长。[S2]", List.of(chunk(1L, "Tianma出货量增长。", 1))));
        assertTrue(error.getMessage().contains("无效来源编号S2"));
    }

    @Test
    void rejectsCitationWhenNumericEvidenceDoesNotMatch()
    {
        KnowledgeChunk source = chunk(1L, "Tianma 2025年出货量为1200 Kpcs，同比增长20%。", 6);
        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> validator.validate("Tianma 2025年出货量为1800 Kpcs，同比增长20%。[S1]", List.of(source)));
        assertTrue(error.getMessage().contains("未在该来源原文中找到足够证据"));
    }

    @Test
    void permitsHeadingAndInsufficientDataStatementWithoutCitation()
    {
        KnowledgeChunk source = chunk(1L, "Tianma LTPS出货量增长20%。", 4);
        KnowledgeCitationValidator.ValidationResult result = validator.validate(
            "## 结论\nTianma LTPS出货量增长20%。[S1]\n资料不足，无法判断AUO增速。", List.of(source));
        assertEquals(1, result.getClaims().size());
    }

    @Test
    void locatesEvidenceWhenPdfExtractionAddsSpacesBetweenChineseCharacters()
    {
        KnowledgeChunk source = chunk(1L, "天 马 L T P S 出 货 量 增 长 20%。", 9);
        KnowledgeCitationValidator.ValidationResult result = validator.validate("天马LTPS出货量增长20%。[S1]", List.of(source));
        assertEquals(1, result.getClaims().size());
        assertEquals(9, result.getEvidenceForSource(1).get(0).get("pageStart"));
    }

    @Test
    void acceptsEquivalentDecimalRatioAndPercentage()
    {
        KnowledgeChunk source = chunk(1L,
            "{\"metric_id\":\"tianma.application_size.仪表.7\",\"shipment\":4340,\"share\":0.137246,"
                + "\"yoy_2025_q1_q3_vs_2024_q1_q3\":-0.095456}", 1);
        KnowledgeCitationValidator.ValidationResult result = validator.validate(
            "Tianma 7英寸仪表出货量为4340千片，占比13.7246%，同比下降9.5456%。[S1]", List.of(source));
        assertEquals(1, result.getClaims().size());
    }

    @Test
    void rejectsDifferentPercentageEvenWhenSourceContainsRatio()
    {
        KnowledgeChunk source = chunk(1L,
            "{\"metric_id\":\"tianma.application_size.仪表.7\",\"share\":0.137246}", 1);
        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> validator.validate("Tianma 7英寸仪表占比为18.7246%。[S1]", List.of(source)));
        assertTrue(error.getMessage().contains("未在该来源原文中找到足够证据"));
    }

    @Test
    void matchesChineseClaimAgainstEnglishStructuredMetricFields()
    {
        KnowledgeChunk source = chunk(1L,
            "{\"metric_id\":\"tianma.application_size.仪表.7\",\"shipment\":4340,"
                + "\"yoy_2025_q1_q3_vs_2024_q1_q3\":-0.095456}", 1);
        KnowledgeCitationValidator.ValidationResult result = validator.validate(
            "该出货同比变化为-0.095456，即-9.5456%。[S1]", List.of(source));
        assertEquals(1, result.getClaims().size());
    }

    @Test
    void permitsKnownSectionHeadingWithoutColonButStillValidatesClaim()
    {
        KnowledgeChunk source = chunk(1L, "Tianma出货量为4340千片。", 1);
        KnowledgeCitationValidator.ValidationResult result = validator.validate(
            "**数据结论**\nTianma出货量为4340千片。[S1]", List.of(source));
        assertEquals(1, result.getClaims().size());
    }

    @Test
    void validatesConciseYoySentenceAgainstStructuredYoyField()
    {
        KnowledgeChunk source = chunk(1L,
            "{\"yoy_2025_q1_q3_vs_2024_q1_q3\":-0.095456}", 1);
        KnowledgeCitationValidator.ValidationResult result = validator.validate(
            "同比变化为-9.5456%，即同比下降9.5456%。[S1]", List.of(source));
        assertEquals(1, result.getClaims().size());
    }

    @Test
    void treatsCalendarYearAndStructuredYYearAliasAsEquivalent()
    {
        KnowledgeChunk source = chunk(1L,
            "{\"metric_id\":\"tianma.technology.ltps.shipment\",\"unit\":\"thousand_units\","
                + "\"periods\":{\"Y24\":9301,\"Y25Q1-Q3\":10323},"
                + "\"yoy_periods\":{\"Y25Q1-Q3\":0.680176}}", 1);
        KnowledgeCitationValidator.ValidationResult result = validator.validate(
            "Tianma 2025年前三季度LTPS出货量为10,323 Kpcs，同比增长68.0%。[S1]", List.of(source));
        assertEquals(1, result.getClaims().size());
    }

    @Test
    void validatesReadableSizeBucketClaimAgainstStructuredSeries()
    {
        KnowledgeChunk source = chunk(1L,
            "{\"metric_id\":\"tianma.technology_size.ltps\",\"technology\":\"LTPS\","
                + "\"series\":[{\"metric_id\":\"tianma.technology_size.ltps.lt8\","
                + "\"unit\":\"thousand_units\",\"periods\":{\"Y24\":3266,"
                + "\"Y25F\":3872,\"Y25Q1-Q3\":2832},\"size_bucket\":\"<8\","
                + "\"label\":\"8”以下\"}]}", 1);
        KnowledgeCitationValidator.ValidationResult result = validator.validate(
            "分尺寸看，8英寸以下LTPS出货量为2,832千片。[S1]", List.of(source));
        assertEquals(1, result.getClaims().size());
    }

    @Test
    void permitsRetrievalStatusStatementWithoutCitation()
    {
        KnowledgeChunk source = chunk(1L, "Tianma出货量为4340千片。", 1);
        KnowledgeCitationValidator.ValidationResult result = validator.validate(
            "Tianma出货量为4340千片。[S1]\n未检索到可用于解释的相关新闻。", List.of(source));
        assertEquals(1, result.getClaims().size());
    }

    private KnowledgeChunk chunk(Long id, String content, int page)
    {
        KnowledgeChunk value = new KnowledgeChunk();
        value.setId(id);
        value.setContent(content);
        value.setSourceSnippet(content);
        value.setSourceName("测试报告.pdf");
        value.setOriginalName("测试原始文件.pdf");
        value.setSourceType("PDF");
        value.setVersionNo("v1");
        value.setPageStart(page);
        value.setPageEnd(page);
        return value;
    }
}
