package com.ruoyi.business.knowledge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.ruoyi.business.knowledge.domain.KnowledgeChunk;
import com.ruoyi.business.knowledge.domain.KnowledgeGraphNode;
import com.ruoyi.business.knowledge.domain.KnowledgeGraphRelation;
import com.ruoyi.business.knowledge.mapper.KnowledgeBaseMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class KnowledgeGraphProposeServiceTest
{
    private KnowledgeBaseMapper mapper;
    private KnowledgeGraphService graphService;
    private KnowledgeGraphProposeService proposeService;
    private final AtomicLong ids = new AtomicLong(1);
    private final List<KnowledgeGraphNode> nodes = new ArrayList<>();
    private final List<KnowledgeGraphRelation> relations = new ArrayList<>();

    @BeforeEach
    void setUp()
    {
        mapper = mock(KnowledgeBaseMapper.class);
        doAnswer(invocation -> {
            KnowledgeGraphNode node = invocation.getArgument(0);
            node.setId(ids.getAndIncrement());
            nodes.add(node);
            return 1;
        }).when(mapper).upsertGraphNode(any(KnowledgeGraphNode.class));
        doAnswer(invocation -> {
            relations.add(invocation.getArgument(0));
            return 1;
        }).when(mapper).insertGraphRelation(any(KnowledgeGraphRelation.class));
        graphService = new KnowledgeGraphService(mapper);
        proposeService = new KnowledgeGraphProposeService(graphService,
            new LlmRuntimeConfiguration("https://api.example.com/v1/chat/completions", "test-model", ""));
    }

    @Test
    void detectEntityTermsIncludesChery()
    {
        List<String> terms = KnowledgeTextProcessor.detectEntityTerms("对比比亚迪和奇瑞2024年销量");
        assertTrue(terms.contains("Chery") || terms.contains("奇瑞"));
        assertTrue(terms.contains("BYD") || terms.contains("比亚迪"));
    }

    @Test
    void commitsCrossSourcePolicyRelationWhenEvidenceMatches()
    {
        KnowledgeChunk policyChunk = chunk(1L, 101L, 201L, "POLICY",
            "《新能源汽车下乡通知》鼓励购买新能源乘用车，覆盖比亚迪等车企。");
        KnowledgeChunk salesChunk = chunk(2L, 102L, 202L, "REPORT",
            "2024年比亚迪新能源乘用车销量达到185.17万辆。");
        Map<String, KnowledgeChunk> labels = Map.of("S1", policyChunk, "S2", salesChunk);

        KnowledgeGraphProposeService.ProposedRelation rel = new KnowledgeGraphProposeService.ProposedRelation(
            "新能源汽车下乡通知", "POLICY", "比亚迪", "COMPANY", "政策关联",
            "鼓励购买新能源乘用车", "S1", "S2");

        String reject = proposeService.validateAndCommit(rel, labels);
        assertNull(reject, () -> "unexpected reject: " + reject);
        assertEquals(1, relations.size());
        assertEquals("政策关联", relations.get(0).getRelationType());
        assertEquals(Long.valueOf(101L), relations.get(0).getSourceId());
        assertTrue(nodes.stream().anyMatch(n -> "POLICY".equals(n.getEntityType())));
        assertTrue(nodes.stream().anyMatch(n -> "COMPANY".equals(n.getEntityType())
            && n.getEntityName().contains("比亚迪")));
    }

    @Test
    void rejectsFabricatedEvidenceWithoutSourceMatch()
    {
        KnowledgeChunk a = chunk(1L, 101L, 201L, "POLICY", "政策文件仅提及购置税减免。");
        KnowledgeChunk b = chunk(2L, 102L, 202L, "REPORT", "奇瑞出口增长。");
        Map<String, KnowledgeChunk> labels = Map.of("S1", a, "S2", b);

        KnowledgeGraphProposeService.ProposedRelation rel = new KnowledgeGraphProposeService.ProposedRelation(
            "虚构政策主体", "POLICY", "奇瑞", "COMPANY", "政策关联",
            "原文从未出现的编造句子XYZ123", "S1", "S2");

        String reject = proposeService.validateAndCommit(rel, labels);
        assertNotNull(reject);
        assertTrue(reject.contains("evidence") || reject.contains("not found") || reject.contains("fromName"));
        assertTrue(relations.isEmpty());
        verify(mapper, never()).insertGraphRelation(any());
    }

    @Test
    void rejectsWrongCompanyModelAttributionAcrossFiles()
    {
        KnowledgeChunk byd = chunk(1L, 130L, 201L, "PDF",
            "比亚迪品牌由王朝与海洋两大产品系列共同构建。");
        KnowledgeChunk chery = chunk(2L, 131L, 202L, "PDF",
            "奇瑞旗下风云与iCAR品牌加速拓展。");
        Map<String, KnowledgeChunk> labels = Map.of("S1", byd, "S2", chery);

        KnowledgeGraphProposeService.ProposedRelation wrong = new KnowledgeGraphProposeService.ProposedRelation(
            "比亚迪", "COMPANY", "iCAR", "MODEL", "关联车型",
            "iCAR品牌", "S1", "S2");
        String reject = proposeService.validateAndCommit(wrong, labels);
        assertNotNull(reject);
        assertTrue(reject.contains("ownership") || reject.contains("co-occur") || reject.contains("COMPANY"));
        assertTrue(relations.isEmpty());
    }

    @Test
    void commitsCompanyModelWhenCoOccurInSameChunk()
    {
        KnowledgeChunk chery = chunk(1L, 131L, 202L, "PDF",
            "奇瑞旗下风云与iCAR品牌加速拓展，奇瑞出口增长。");
        KnowledgeChunk byd = chunk(2L, 130L, 201L, "PDF",
            "比亚迪王朝与海洋系列。");
        Map<String, KnowledgeChunk> labels = Map.of("S1", chery, "S2", byd);

        KnowledgeGraphProposeService.ProposedRelation ok = new KnowledgeGraphProposeService.ProposedRelation(
            "奇瑞", "COMPANY", "iCAR", "MODEL", "关联车型",
            "奇瑞旗下风云与iCAR", "S1", "S2");
        assertNull(proposeService.validateAndCommit(ok, labels), "should commit same-chunk ownership");
        assertEquals(1, relations.size());
        assertEquals("关联车型", relations.get(0).getRelationType());
        assertEquals(Long.valueOf(131L), relations.get(0).getSourceId());
    }

    @Test
    void rejectsInvalidTypePairForSalesEdge()
    {
        KnowledgeChunk a = chunk(1L, 101L, 201L, "POLICY", "政策提及比亚迪。");
        KnowledgeChunk b = chunk(2L, 102L, 202L, "REPORT", "比亚迪销量上升。");
        Map<String, KnowledgeChunk> labels = Map.of("S1", a, "S2", b);

        KnowledgeGraphProposeService.ProposedRelation rel = new KnowledgeGraphProposeService.ProposedRelation(
            "政策A", "POLICY", "比亚迪", "COMPANY", "关联车型",
            "比亚迪", "S1", "S2");
        String reject = proposeService.validateAndCommit(rel, labels);
        assertNotNull(reject);
        assertTrue(reject.contains("关联车型") || reject.contains("COMPANY"));
    }

    @Test
    void skipsSameSourceEdges()
    {
        KnowledgeChunk only = chunk(1L, 101L, 201L, "REPORT",
            "比亚迪与奇瑞同在一份销量报告中被提及。");
        Map<String, KnowledgeChunk> labels = Map.of("S1", only, "S2", only);

        KnowledgeGraphProposeService.ProposedRelation rel = new KnowledgeGraphProposeService.ProposedRelation(
            "比亚迪", "COMPANY", "奇瑞", "COMPANY", "关联企业",
            "比亚迪与奇瑞", "S1", "S2");

        String reject = proposeService.validateAndCommit(rel, labels);
        assertNotNull(reject);
        assertTrue(reject.contains("bridge") || reject.contains("different sourceId") || reject.contains("cross"));
        assertTrue(relations.isEmpty());
    }

    @Test
    void parseRelationsReadsModelJson()
    {
        List<KnowledgeGraphProposeService.ProposedRelation> list = proposeService.parseRelations("""
            {"relations":[{"fromName":"政策A","fromType":"POLICY","toName":"奇瑞","toType":"COMPANY",
            "relationType":"政策关联","evidence":"奇瑞","fromCitation":"S2","toCitation":"S1"}]}
            """);
        assertEquals(1, list.size());
        assertEquals("奇瑞", list.get(0).toName());
        assertEquals("S2", list.get(0).fromCitation());
    }

    @Test
    void proposeAndCommitSkipsWhenFewerThanTwoSources()
    {
        KnowledgeChunk a = chunk(1L, 101L, 201L, "REPORT", "奇瑞销量增长。");
        KnowledgeChunk b = chunk(2L, 101L, 202L, "REPORT", "奇瑞出口增长。");
        List<Map<String, Object>> citations = List.of(
            citation("S1", a), citation("S2", b));
        List<String> warnings = new ArrayList<>();
        List<Map<String, Object>> logs = new ArrayList<>();

        KnowledgeGraphProposeService.ProposeResult result = proposeService.proposeAndCommit(
            "奇瑞销量如何", "奇瑞增长", citations, List.of(a, b), warnings, logs);

        // 单源：可做归属重抽，但不跑跨文档 LLM 提议
        assertTrue(logs.stream().anyMatch(l -> String.valueOf(l.get("detail")).contains("single-source")));
        assertTrue(logs.stream().noneMatch(l -> "SUCCESS".equals(l.get("status"))
            && String.valueOf(l.get("title")).contains("图谱提议写入")));
    }

    @Test
    void reindexCitedChunksLinksChanganModelByCooccurrence()
    {
        KnowledgeChunk chunk = chunk(9L, 200L, 300L, "PDF",
            "长安汽车旗下UNI-V与CS75车型热销，长安出口同步增长。");
        int indexed = graphService.reindexCitedChunks(List.of(chunk));
        assertEquals(1, indexed);
        assertTrue(nodes.stream().anyMatch(n -> "COMPANY".equals(n.getEntityType())
            && (n.getEntityName().contains("Changan") || n.getEntityName().contains("长安"))));
        assertTrue(nodes.stream().anyMatch(n -> "MODEL".equals(n.getEntityType())
            && (n.getEntityName().contains("UNI-V") || n.getEntityName().contains("CS75"))));
        assertTrue(relations.stream().anyMatch(r -> "关联车型".equals(r.getRelationType())));
    }

    @Test
    void reindexCitedChunksLinksCheryModelByCooccurrence()
    {
        KnowledgeChunk chunk = chunk(10L, 131L, 202L, "PDF",
            "奇瑞旗下风云与iCAR品牌加速拓展，奇瑞汽车出口增长。");
        graphService.reindexCitedChunks(List.of(chunk));
        assertTrue(nodes.stream().anyMatch(n -> "COMPANY".equals(n.getEntityType())
            && (n.getEntityName().contains("Chery") || n.getEntityName().contains("奇瑞"))));
        assertTrue(relations.stream().anyMatch(r -> "关联车型".equals(r.getRelationType())));
    }

    private static KnowledgeChunk chunk(Long id, Long sourceId, Long versionId, String type, String content)
    {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(id);
        chunk.setSourceId(sourceId);
        chunk.setVersionId(versionId);
        chunk.setSourceType(type);
        chunk.setSourceName("src-" + sourceId);
        chunk.setOriginalName("file-" + sourceId + ".pdf");
        chunk.setContent(content);
        chunk.setSourceSnippet(content);
        return chunk;
    }

    private static Map<String, Object> citation(String label, KnowledgeChunk chunk)
    {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("citationLabel", label);
        value.put("id", chunk.getId());
        value.put("sourceId", chunk.getSourceId());
        value.put("versionId", chunk.getVersionId());
        value.put("sourceType", chunk.getSourceType());
        value.put("sourceName", chunk.getSourceName());
        value.put("originalName", chunk.getOriginalName());
        value.put("sourceSnippet", chunk.getSourceSnippet());
        return value;
    }
}
