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

    public KnowledgeBase selectKnowledgeBaseById(Long id);

    public KnowledgeBase selectKnowledgeBaseBySourceCode(String sourceCode);

    public int insertKnowledgeBase(KnowledgeBase knowledgeBase);

    public int updateKnowledgeBase(KnowledgeBase knowledgeBase);

    public int deleteKnowledgeBaseById(Long id);

    public int deleteKnowledgeBaseByIds(Long[] ids);

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
        @Param("normalizedLiteral") String normalizedLiteral);

    List<KnowledgeChunk> selectCurrentMetricChunks(@Param("roleIds") List<Long> roleIds,
        @Param("admin") boolean admin, @Param("metricTerms") List<String> metricTerms,
        @Param("limit") int limit);

    List<KnowledgeChunk> selectMetricFragments(@Param("versionId") Long versionId,
        @Param("metricId") String metricId, @Param("titlePath") String titlePath);

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
}
