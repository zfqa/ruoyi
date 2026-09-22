package com.ruoyi.business.knowledge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import com.ruoyi.business.knowledge.domain.KnowledgeChunk;
import com.ruoyi.business.knowledge.service.QuestionIntentAnalyzer.Domain;
import com.ruoyi.business.knowledge.service.QuestionIntentAnalyzer.Kind;
import org.junit.jupiter.api.Test;

/** 意图 → 抽数 → 程序计算通用链路，不依赖具体题目特判。 */
class KnowledgeProgramAnswerPipelineTest
{
    private final QuestionIntentAnalyzer analyzer = new QuestionIntentAnalyzer();
    private final KnowledgeProgramAnswerService program = new KnowledgeProgramAnswerService();

    @Test
    void staffSumUsesSharedMetricPath()
    {
        var plan = analyzer.analyze("比亚迪母公司生产人员与销售人员分别是多少，合计多少？");
        assertEquals(Domain.HUMAN_RESOURCES, plan.domain());
        assertEquals(Kind.CALCULATION, plan.kind());
        assertTrue(plan.needSum());
        assertTrue(plan.metricHints().contains("生产人员"));
        assertTrue(plan.metricHints().contains("销售人员"));

        KnowledgeChunk chunk = chunk(
            "报告期末母公司在职员工的数量（人）2075\n生产人员665812\n销售人员40348");
        var answer = program.tryAnswer("生产人员与销售人员分别是多少，合计多少？", List.of(chunk));
        assertNotNull(answer);
        assertTrue(answer.warnings() == null || answer.warnings().isEmpty(),
            () -> "unexpected warnings: " + answer.warnings());
        assertFalse(answer.partial(), () -> answer.text());
        assertTrue(answer.text().contains("665812"));
        assertTrue(answer.text().contains("40348"));
        assertTrue(answer.text().contains("706160"));
    }

    @Test
    void impairmentUsesPreparationNotFixedAssetNoise()
    {
        var plan = analyzer.analyze("信用减值准备与资产减值准备分别是多少，合计多少？");
        assertEquals(Domain.FINANCIAL, plan.domain());
        KnowledgeChunk chunk = chunk(
            "固定资产减值准备 12,000\n加：信用减值准备 1,234,567\n加：资产减值准备 2,345,678\n存货跌价准备 9");
        var answer = program.tryAnswer(plan.question(), List.of(chunk));
        assertNotNull(answer);
        assertTrue(answer.text().contains("1234567") || answer.text().contains("1,234,567"));
        assertTrue(answer.text().contains("2345678") || answer.text().contains("2,345,678"));
        assertTrue(answer.text().contains("3580245"));
        assertFalse(answer.text().contains("12000") || answer.text().contains("12,000"));
    }

    @Test
    void salesStaffIsNotVehicleSalesIntent()
    {
        var plan = analyzer.analyze("销售人员有多少人？");
        assertEquals(Domain.HUMAN_RESOURCES, plan.domain());
        assertFalse(plan.salesVolume());
    }

    @Test
    void researchListMarksContinuationAsPartial()
    {
        KnowledgeChunk chunk = chunk("主要研发项目\n项目名称：第二代刀片电池\n项目名称：智能驾驶\n续表");
        var answer = program.tryAnswer("主要研发项目有哪些？请全部列出", List.of(chunk));
        assertNotNull(answer);
        assertTrue(answer.partial());
        assertTrue(answer.text().contains("第二代刀片电池"));
    }

    @Test
    void parentAndGroupHeadcountStaySeparate()
    {
        var plan = analyzer.analyze("母公司在职员工和集团在职员工合计分别是多少？不要把两个口径混在一起。");
        assertEquals(Domain.HUMAN_RESOURCES, plan.domain());
        assertFalse(plan.needSum());
        assertTrue(plan.metricHints().contains("母公司在职员工"));
        assertTrue(plan.metricHints().contains("在职员工的数量合计"));

        KnowledgeChunk prose = chunk(
            "报告期末母公司在职员工的数量（人）2,075\n报告期末主要子公司在职员工的数量（人）867,547\n报告期末在职员工的数量合计（人）869,622");
        KnowledgeChunk noisy = chunk(
            "标题: 表格 1\n数量（人）15\n报告期末母公司在职员工的数量（人）: 报告期末在职员工的数量合计（人）\n2,075: 869,622");
        var answer = program.tryAnswer(plan.question(), List.of(noisy, prose));
        assertNotNull(answer);
        assertFalse(answer.partial(), () -> answer.text() + " / " + answer.warnings());
        assertTrue(answer.text().contains("2075") || answer.text().contains("2,075"));
        assertTrue(answer.text().contains("869622") || answer.text().contains("869,622"));
        assertFalse(answer.text().contains("15人") || answer.text().matches("(?s).*数量为 15.*"));
    }

    private static KnowledgeChunk chunk(String content)
    {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(1L);
        chunk.setSourceId(130L);
        chunk.setVersionId(137L);
        chunk.setChunkNo(1);
        chunk.setContent(content);
        chunk.setOriginalName("比亚迪2025年年度报告.pdf");
        chunk.setSourceType("PDF");
        chunk.setPageStart(100);
        return chunk;
    }
}
