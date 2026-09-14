package com.ruoyi.business.knowledge.service;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.ruoyi.business.knowledge.mapper.KnowledgeBaseMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class KnowledgeQaAuditServiceTest
{
    @Test
    void writesSessionClaimsAndCitationsInOneOrderedOperation()
    {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("citationLabel", "S1"); evidence.put("chunkId", 8L); evidence.put("sourceName", "报告");
        evidence.put("pageStart", 3); evidence.put("pageEnd", 3); evidence.put("startOffset", 10);
        evidence.put("endOffset", 20); evidence.put("evidenceSnippet", "证据");
        Map<String, Object> claim = Map.of("claimText", "事实结论", "verified", true, "evidences", List.of(evidence));
        Instant now = Instant.now();

        new KnowledgeQaAuditService(mapper).complete("task1", 100, "完成", "{}", now, now,
            Map.of("claims", List.of(claim)));

        InOrder order = inOrder(mapper);
        order.verify(mapper).updateQaSession(eq("task1"), eq("SUCCESS"), eq(100), eq("完成"), eq(""),
            eq("{}"), eq(now), eq(now));
        order.verify(mapper).deleteQaCitations("task1");
        order.verify(mapper).deleteQaClaims("task1");
        order.verify(mapper).insertQaClaim("task1", 1, "事实结论", true);
        order.verify(mapper).insertQaCitation("task1", 1, "S1", 8L, "报告", 3, 3, 10, 20, "证据");
    }

    @Test
    void removesExpiredChildrenBeforeSessions()
    {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        Instant cutoff = Instant.now();
        new KnowledgeQaAuditService(mapper).cleanup(cutoff);
        InOrder order = inOrder(mapper);
        order.verify(mapper).deleteQaCitationsBefore(cutoff);
        order.verify(mapper).deleteQaClaimsBefore(cutoff);
        order.verify(mapper).deleteQaSessionsBefore(cutoff);
    }
}
