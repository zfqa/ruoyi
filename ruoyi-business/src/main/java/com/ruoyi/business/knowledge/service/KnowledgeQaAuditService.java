package com.ruoyi.business.knowledge.service;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import com.ruoyi.business.knowledge.mapper.KnowledgeBaseMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 将成功问答、逐句结论和引用作为一个事务提交，避免部分审计记录。 */
@Service
public class KnowledgeQaAuditService
{
    private final KnowledgeBaseMapper mapper;

    public KnowledgeQaAuditService(KnowledgeBaseMapper mapper) { this.mapper = mapper; }

    @Transactional(rollbackFor = Exception.class)
    public void complete(String taskId, int progress, String stage, String snapshotJson,
        Instant startedAt, Instant finishedAt, Map<String, Object> result)
    {
        mapper.updateQaSession(taskId, "SUCCESS", progress, stage, "", snapshotJson, startedAt, finishedAt);
        mapper.deleteQaCitations(taskId);
        mapper.deleteQaClaims(taskId);
        writeClaims(taskId, result);
    }

    @Transactional(rollbackFor = Exception.class)
    public void cleanup(Instant cutoff)
    {
        mapper.deleteQaCitationsBefore(cutoff);
        mapper.deleteQaClaimsBefore(cutoff);
        mapper.deleteQaSessionsBefore(cutoff);
    }

    @SuppressWarnings("unchecked")
    private void writeClaims(String taskId, Map<String, Object> result)
    {
        Object rawClaims = result == null ? null : result.get("claims");
        if (!(rawClaims instanceof List<?> claims)) return;
        int claimNo = 0;
        for (Object rawClaim : claims)
        {
            if (!(rawClaim instanceof Map<?, ?>)) continue;
            Map<String, Object> claim = (Map<String, Object>) rawClaim;
            claimNo++;
            mapper.insertQaClaim(taskId, claimNo, string(claim.get("claimText")),
                Boolean.parseBoolean(string(claim.get("verified"))));
            Object rawEvidences = claim.get("evidences");
            if (!(rawEvidences instanceof List<?> evidences)) continue;
            for (Object rawEvidence : evidences)
            {
                if (!(rawEvidence instanceof Map<?, ?>)) continue;
                Map<String, Object> evidence = (Map<String, Object>) rawEvidence;
                mapper.insertQaCitation(taskId, claimNo, string(evidence.get("citationLabel")),
                    longValue(evidence.get("chunkId")), string(evidence.get("sourceName")),
                    intValue(evidence.get("pageStart")), intValue(evidence.get("pageEnd")),
                    intValue(evidence.get("startOffset")), intValue(evidence.get("endOffset")),
                    string(evidence.get("evidenceSnippet")));
            }
        }
    }

    private String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private Long longValue(Object value) { return value == null ? null : ((Number) value).longValue(); }
    private Integer intValue(Object value) { return value == null ? null : ((Number) value).intValue(); }
}
