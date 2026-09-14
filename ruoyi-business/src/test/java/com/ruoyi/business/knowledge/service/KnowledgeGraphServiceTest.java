package com.ruoyi.business.knowledge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ruoyi.business.knowledge.domain.KnowledgeBase;
import com.ruoyi.business.knowledge.domain.KnowledgeChunk;
import com.ruoyi.business.knowledge.domain.KnowledgeGraphNode;
import com.ruoyi.business.knowledge.domain.KnowledgeGraphRelation;
import com.ruoyi.business.knowledge.domain.KnowledgeVersion;
import com.ruoyi.business.knowledge.mapper.KnowledgeBaseMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;

class KnowledgeGraphServiceTest
{
    @Test
    void returnsTheExactRequestedParagraphWindowForCitationClick()
    {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(88L); chunk.setSourceName("终稿报告"); chunk.setOriginalName("终稿.pdf");
        chunk.setVersionNo("v1"); chunk.setPageStart(14); chunk.setPageEnd(14);
        String paragraph = "Tianma 2025年前三季度LTPS出货量为10,323 Kpcs，同比增长68.0%。";
        chunk.setContent("上一段背景。\n" + paragraph + "\n下一段说明。");
        when(mapper.selectAuthorizedChunkById(eq(88L), anyList(), eq(false))).thenReturn(chunk);
        int start = chunk.getContent().indexOf(paragraph);

        Map<String, Object> result = new KnowledgeGraphService(mapper).evidence(
            88L, start, start + paragraph.length(), List.of(2L), false);

        assertEquals(paragraph, result.get("highlightedText"));
        assertEquals(start, result.get("startOffset"));
        assertEquals(start + paragraph.length(), result.get("endOffset"));
        assertEquals(14, result.get("pageStart"));
    }

    @Test
    void attachesSixKnownEntityTypesToTraceableGraph()
    {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        AtomicLong ids = new AtomicLong(1);
        List<KnowledgeGraphNode> nodes = new ArrayList<>();
        List<KnowledgeGraphRelation> relations = new ArrayList<>();
        doAnswer(invocation -> { KnowledgeGraphNode node=invocation.getArgument(0); node.setId(ids.getAndIncrement()); nodes.add(node); return 1; })
            .when(mapper).upsertGraphNode(any(KnowledgeGraphNode.class));
        doAnswer(invocation -> { relations.add(invocation.getArgument(0)); return 1; })
            .when(mapper).insertGraphRelation(any(KnowledgeGraphRelation.class));

        KnowledgeBase source = new KnowledgeBase();
        source.setId(10L); source.setSourceName("车市新闻"); source.setSourceType("NEWS");
        KnowledgeVersion version = new KnowledgeVersion();
        version.setId(20L); version.setOriginalName("2023Q3车市快讯");
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(30L); chunk.setContent("2023年Q3新闻：企业：比亚迪，车型：海豚，销量：12万辆；一季度财报显示增长，相关依据为《新能源汽车产业发展政策》。");

        new KnowledgeGraphService(mapper).indexChunk(source, version, chunk);

        Set<String> types = nodes.stream().map(KnowledgeGraphNode::getEntityType).collect(Collectors.toSet());
        assertTrue(types.containsAll(Set.of("COMPANY", "MODEL", "SALES", "NEWS", "FINANCIAL", "POLICY")));
        assertTrue(relations.size() >= 5);
        assertTrue(relations.stream().allMatch(r -> r.getChunkId().equals(30L)));
        assertTrue(relations.stream().allMatch(r -> "2023 Q3".equals(r.getPeriod())));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aggregatesDuplicateVisibleEdgesWithoutLosingEvidence()
    {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        Map<String, Object> first = relationRow(1L, 10L, "来源提及", 100L, 1000L, "报告A");
        Map<String, Object> second = relationRow(2L, 10L, "来源提及", 101L, 1001L, "报告B");
        when(mapper.selectGraphRelations(anyString(), anyString(), isNull(), anyList(), anyBoolean(), anyInt()))
            .thenReturn(List.of(first, second));

        Map<String, Object> graph = new KnowledgeGraphService(mapper).graph("", "", null, List.of(), true, 100);
        List<Map<String, Object>> links = (List<Map<String, Object>>) graph.get("links");

        assertEquals(1, links.size());
        assertEquals(2, links.get(0).get("mentionCount"));
        assertEquals(2, links.get(0).get("sourceCount"));
        assertEquals(2, ((List<?>) links.get(0).get("evidences")).size());
    }

    @Test
    void indexesIndependentPolicySourceWithPolicyDataType()
    {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        AtomicLong ids = new AtomicLong(1);
        List<KnowledgeGraphNode> nodes = new ArrayList<>();
        List<KnowledgeGraphRelation> relations = new ArrayList<>();
        doAnswer(invocation -> { KnowledgeGraphNode node=invocation.getArgument(0); node.setId(ids.getAndIncrement()); nodes.add(node); return 1; })
            .when(mapper).upsertGraphNode(any(KnowledgeGraphNode.class));
        doAnswer(invocation -> { relations.add(invocation.getArgument(0)); return 1; })
            .when(mapper).insertGraphRelation(any(KnowledgeGraphRelation.class));

        KnowledgeBase source = new KnowledgeBase();
        source.setId(11L); source.setSourceName("汽车以旧换新政策"); source.setSourceType("POLICY");
        KnowledgeVersion version = new KnowledgeVersion();
        version.setId(21L); version.setOriginalName("政策原文");
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(31L);
        chunk.setContent("2025年《汽车以旧换新政策通知》支持比亚迪等汽车企业扩大新能源汽车消费和有效供给。");

        new KnowledgeGraphService(mapper).indexChunk(source, version, chunk);

        assertTrue(nodes.stream().anyMatch(n -> "POLICY".equals(n.getEntityType())
            && "汽车以旧换新政策".equals(n.getEntityName())));
        assertTrue(nodes.stream().anyMatch(n -> "COMPANY".equals(n.getEntityType())
            && "BYD".equals(n.getEntityName())));
        assertTrue(relations.stream().allMatch(r -> "POLICY".equals(r.getDataType())));
        assertTrue(relations.stream().allMatch(r -> "2025".equals(r.getPeriod())));
    }

    @Test
    void returnsAuthorizedPdfWithoutExposingOrAcceptingAnOutsidePath() throws Exception
    {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        Path profile = Path.of("target", "knowledge-graph-file-test").toAbsolutePath().normalize();
        KnowledgeFileStorage storage = new KnowledgeFileStorage(profile.toString());
        Path controlled = profile.resolve("knowledge/pdf/source.pdf");
        Files.createDirectories(controlled.getParent());
        Files.writeString(controlled, "%PDF-test");
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(88L); chunk.setVersionId(7L); chunk.setSourceType("PDF");
        KnowledgeVersion version = new KnowledgeVersion();
        version.setId(7L); version.setStoredPath(controlled.toString()); version.setOriginalName("终稿.pdf");
        when(mapper.selectAuthorizedChunkById(eq(88L), anyList(), eq(false))).thenReturn(chunk);
        when(mapper.selectVersionById(7L)).thenReturn(version);

        KnowledgeGraphService.SourceFile result = new KnowledgeGraphService(mapper, storage)
            .sourceFile(88L, List.of(2L), false);

        assertEquals(controlled, result.path());
        assertEquals("终稿.pdf", result.originalName());
        version.setStoredPath(profile.resolve("outside.pdf").toString());
        assertThrows(IllegalArgumentException.class,
            () -> new KnowledgeGraphService(mapper, storage).sourceFile(88L, List.of(2L), false));
        Files.deleteIfExists(controlled);
        Files.deleteIfExists(controlled.getParent());
        Files.deleteIfExists(controlled.getParent().getParent());
        Files.deleteIfExists(profile);
    }

    private Map<String, Object> relationRow(Long relationId, Long toId, String type,
        Long sourceId, Long chunkId, String sourceName)
    {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("relationId", relationId); row.put("relationType", type); row.put("sourceId", sourceId);
        row.put("fromId", 1L); row.put("fromName", "源文档"); row.put("fromType", "REPORT");
        row.put("toId", toId); row.put("toName", "Tianma"); row.put("toType", "COMPANY");
        row.put("chunkId", chunkId); row.put("sourceName", sourceName); row.put("evidenceSnippet", "Tianma");
        return row;
    }
}
