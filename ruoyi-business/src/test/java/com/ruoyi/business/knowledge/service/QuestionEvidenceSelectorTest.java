package com.ruoyi.business.knowledge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.List;
import com.ruoyi.business.knowledge.domain.KnowledgeChunk;
import org.junit.jupiter.api.Test;

class QuestionEvidenceSelectorTest
{
    @Test
    void keepsOnlyDocumentThatContainsTheRareTerm()
    {
        KnowledgeChunk byd = chunk(130L, "比亚迪2025年年度报告.pdf", "2025年销量增长，营业收入提高。");
        KnowledgeChunk chery = chunk(131L, "奇瑞汽车研究报告.pdf", "2025年全球新能源乘用车销量增长，渗透率约33%。");
        List<KnowledgeChunk> kept = QuestionEvidenceSelector.retainDocumentsWithRarestTerms(
            "研报里2025年全球新能源乘用车销量大约多少万辆，渗透率大约多少？", List.of(byd, chery));
        assertEquals(1, kept.size());
        assertEquals(131L, kept.get(0).getSourceId());
    }

    @Test
    void keepsBothDocumentsWhenTermsAreShared()
    {
        KnowledgeChunk byd = chunk(130L, "比亚迪年报.pdf", "营业收入为100。");
        KnowledgeChunk chery = chunk(131L, "奇瑞研报.pdf", "营业收入为200。");
        List<KnowledgeChunk> kept = QuestionEvidenceSelector.retainDocumentsWithRarestTerms(
            "营业收入是多少？", List.of(byd, chery));
        assertEquals(2, kept.size());
    }

    @Test
    void extractsSpecificTermsAndDropsFunctionWords()
    {
        List<String> terms = QuestionEvidenceSelector.contentTerms("奇瑞墨西哥瓜达拉哈拉工厂规划产能是多少万辆？");
        assertTrue(terms.contains("瓜达拉哈拉"));
        assertTrue(terms.stream().noneMatch(term -> term.equals("多少") || term.equals("大约")));
    }

    private static KnowledgeChunk chunk(Long sourceId, String name, String content)
    {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setSourceId(sourceId);
        chunk.setOriginalName(name);
        chunk.setContent(content);
        return chunk;
    }
}
