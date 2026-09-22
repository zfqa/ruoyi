package com.ruoyi.business.knowledge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import org.junit.jupiter.api.Test;

class KnowledgeTextProcessorTest
{
    @Test
    void detectsMakerAliasesAndSpecialTechnologyTerm()
    {
        assertTrue(KnowledgeTextProcessor.detectEntityTerms("天马增长原因").contains("Tianma"));
        assertTrue(KnowledgeTextProcessor.detectEntityTerms("CSOT与京东方对比").contains("CSOT"));
        assertTrue(KnowledgeTextProcessor.detectEntityTerms("CSOT与京东方对比").contains("BOE"));
        assertTrue(KnowledgeTextProcessor.detectEntityTerms("小鹏近期交付与市场动作").contains("XPeng"));
        assertTrue(KnowledgeTextProcessor.detectEntityTerms("小鹏近期交付与市场动作").contains("小鹏"));
        assertEquals("asi", KnowledgeTextProcessor.normalizedLiteral("8英寸以下 a-Si 出货"));
    }

    @Test
    void removesStandaloneChartAxesButKeepsNarrative()
    {
        String raw = "AUO增长点分析一：产品线\n14\n1.3\n0 500 1,000 1,500\nAUO Y25 Q1-Q3尺寸别分布情况\n"
            + "Tianma增长主要由12-15英寸LTPS产品驱动，同比+127%。";
        String cleaned = KnowledgeTextProcessor.cleanPdfText(raw);
        assertFalse(cleaned.contains("\n1.3\n"));
        assertFalse(cleaned.contains("0 500 1,000"));
        assertTrue(cleaned.contains("AUO Y25 Q1-Q3尺寸别分布情况"));
        assertTrue(cleaned.contains("Tianma增长主要由"));
    }

    @Test
    void joinsPdfLineWrapInsideASentence()
    {
        String raw = "2022 年 9 月，奇瑞宣布启动“瑶光 2025”战略计划，确立了火星架构（车型\n"
            + "平台+EEA架构）、鲲鹏动力（动力解决方案）、雄狮科技（汽车智能化板块）、银河生态（合作生态）四大技\n"
            + "术基座，并宣布在未来5 年内研发投资超千亿。\n"
            + "图 50: 奇瑞五大技术领域";
        String joined = KnowledgeTextProcessor.joinWrappedLines(raw);
        assertTrue(joined.contains("火星架构（车型平台+EEA架构）、鲲鹏动力（动力解决方案）、雄狮科技（汽车智能化板块）、银河生态（合作生态）四大技术基座"));
        assertTrue(joined.contains("\n图 50: 奇瑞五大技术领域"));
    }

    @Test
    void snippetStartsNearRequestedMaker()
    {
        String text = "AUO无关说明。" + "无关内容".repeat(80)
            + "Tianma出货数据变化背后的主要因素：12-15英寸LTPS产品增长。";
        String snippet = KnowledgeTextProcessor.buildSnippet(text, "Tianma增长原因", List.of("Tianma", "天马"), 180);
        assertTrue(snippet.contains("Tianma出货数据变化"));
        assertFalse(snippet.startsWith("AUO无关说明"));
    }
}
