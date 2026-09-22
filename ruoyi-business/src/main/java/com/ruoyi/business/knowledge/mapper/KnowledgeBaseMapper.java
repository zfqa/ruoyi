package com.ruoyi.business.knowledge.mapper;

import java.util.List;
import com.ruoyi.business.knowledge.domain.KnowledgeBase;
import com.ruoyi.business.knowledge.domain.KnowledgeChunk;
import com.ruoyi.business.knowledge.domain.KnowledgeIngestTask;
import com.ruoyi.business.knowledge.domain.KnowledgeVersion;
import com.ruoyi.business.knowledge.domain.KnowledgeGraphNode;
import com.ruoyi.business.knowledge.domain.KnowledgeGraphRelation;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * 固定文件知识库及来源展示 数据层
 * 
 * @author ruoyi
 */
public interface KnowledgeBaseMapper
{
    public List<KnowledgeBase> selectKnowledgeBaseList(KnowledgeBase knowledgeBase);

    List<KnowledgeBase> selectAuthorizedKnowledgeBaseList(@Param("filter") KnowledgeBase filter,
        @Param("roleIds") List<Long> roleIds, @Param("admin") boolean admin);

    public KnowledgeBase selectKnowledgeBaseById(Long id);

    KnowledgeBase selectAuthorizedKnowledgeBaseById(@Param("id") Long id,
        @Param("roleIds") List<Long> roleIds, @Param("admin") boolean admin);

    public KnowledgeBase selectKnowledgeBaseBySourceCode(String sourceCode);

    public int insertKnowledgeBase(KnowledgeBase knowledgeBase);

    public int updateKnowledgeBase(KnowledgeBase knowledgeBase);

    int promoteCurrentVersionIfNewer(@Param("sourceId") Long sourceId, @Param("versionId") Long versionId,
        @Param("remark") String remark);

    public int deleteKnowledgeBaseById(Long id);

    public int deleteKnowledgeBaseByIds(Long[] ids);

    int deleteIngestTasksBySourceId(Long sourceId);

    int deleteVersionsBySourceId(Long sourceId);

    KnowledgeVersion selectVersionById(Long id);

    KnowledgeVersion selectVersionByHash(@Param("sourceId") Long sourceId, @Param("contentSha256") String contentSha256);

    List<KnowledgeVersion> selectVersionsBySourceId(Long sourceId);

    int insertVersion(KnowledgeVersion version);

    int updateVersion(KnowledgeVersion version);

    int insertIngestTask(KnowledgeIngestTask task);

    int updateIngestTask(KnowledgeIngestTask task);

    KnowledgeIngestTask selectIngestTaskById(Long id);

    KnowledgeIngestTask selectIngestTaskByVersionId(Long versionId);

    int deleteChunksByVersionId(Long versionId);

    int insertChunk(KnowledgeChunk chunk);

    List<KnowledgeChunk> searchChunks(@Param("query") String query,
        @Param("sourceType") String sourceType, @Param("roleIds") List<Long> roleIds,
        @Param("admin") boolean admin, @Param("limit") int limit,
        @Param("entityTerms") List<String> entityTerms,
        @Param("normalizedLiteral") String normalizedLiteral,
        @Param("sourceId") Long sourceId, @Param("versionId") Long versionId,
        @Param("requireEntityInContent") boolean requireEntityInContent);

    List<KnowledgeChunk> selectCurrentMetricChunks(@Param("roleIds") List<Long> roleIds,
        @Param("admin") boolean admin, @Param("metricTerms") List<String> metricTerms,
        @Param("limit") int limit);

    List<KnowledgeChunk> selectMetricFragments(@Param("versionId") Long versionId,
        @Param("metricId") String metricId, @Param("titlePath") String titlePath);

    List<KnowledgeChunk> selectChunksByVersionId(@Param("versionId") Long versionId);

    KnowledgeChunk selectChunkByVersionAndNo(@Param("versionId") Long versionId, @Param("chunkNo") Integer chunkNo);

    KnowledgeChunk selectAuthorizedChunkById(@Param("id") Long id,
        @Param("roleIds") List<Long> roleIds, @Param("admin") boolean admin);

    int upsertGraphNode(KnowledgeGraphNode node);

    int insertGraphRelation(KnowledgeGraphRelation relation);

    int deleteGraphRelationsByVersionId(Long versionId);

    List<Map<String, Object>> selectGraphRelations(@Param("period") String period,
        @Param("dataType") String dataType, @Param("centerId") Long centerId,
        @Param("roleIds") List<Long> roleIds, @Param("admin") boolean admin,
        @Param("limit") int limit);

    List<Map<String, Object>> selectGraphRelationsByChunkIds(@Param("chunkIds") List<Long> chunkIds,
        @Param("roleIds") List<Long> roleIds, @Param("admin") boolean admin,
        @Param("limit") int limit);

    List<KnowledgeChunk> selectCurrentChunks(@Param("limit") int limit);

    int insertQaSession(@Param("taskId") String taskId, @Param("owner") String owner,
        @Param("question") String question, @Param("sourceType") String sourceType,
        @Param("includeNews") boolean includeNews, @Param("snapshotJson") String snapshotJson);

    int updateQaSession(@Param("taskId") String taskId, @Param("status") String status,
        @Param("progress") int progress, @Param("currentStage") String currentStage,
        @Param("errorMessage") String errorMessage, @Param("snapshotJson") String snapshotJson,
        @Param("startedAt") java.time.Instant startedAt, @Param("finishedAt") java.time.Instant finishedAt);

    Map<String, Object> selectQaSession(@Param("taskId") String taskId);
    List<Map<String, Object>> selectQaSessions(@Param("owner") String owner,
        @Param("admin") boolean admin, @Param("limit") int limit);

    int deleteQaClaims(@Param("taskId") String taskId);
    int deleteQaCitations(@Param("taskId") String taskId);
    int insertQaClaim(@Param("taskId") String taskId, @Param("claimNo") int claimNo,
        @Param("claimText") String claimText, @Param("verified") boolean verified);
    int insertQaCitation(@Param("taskId") String taskId, @Param("claimNo") int claimNo,
        @Param("citationLabel") String citationLabel, @Param("chunkId") Long chunkId,
        @Param("sourceName") String sourceName, @Param("pageStart") Integer pageStart,
        @Param("pageEnd") Integer pageEnd, @Param("startOffset") Integer startOffset,
        @Param("endOffset") Integer endOffset, @Param("evidenceSnippet") String evidenceSnippet);
    int deleteQaCitationsBefore(@Param("cutoff") java.time.Instant cutoff);
    int deleteQaClaimsBefore(@Param("cutoff") java.time.Instant cutoff);
    int deleteQaSessionsBefore(@Param("cutoff") java.time.Instant cutoff);

    int deleteFactsByVersionId(Long versionId);

    int insertFact(com.ruoyi.business.knowledge.domain.KnowledgeFact fact);

    List<com.ruoyi.business.knowledge.domain.KnowledgeFact> searchCurrentFacts(@Param("hints") List<String> hints,
        @Param("sourceIds") List<Long> sourceIds);
}
