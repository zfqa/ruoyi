package com.ruoyi.business.knowledge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class KnowledgeGraphVisibleTypesTest
{
    @Test
    @SuppressWarnings("unchecked")
    void assemblesOnlySixEntityTypesAndDropsIsolatedNodes()
    {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        when(mapper.selectGraphRelations(anyString(), anyString(), isNull(), anyList(), anyBoolean(), anyInt()))
            .thenReturn(List.of(
                relationRow(1L, 1L, "BYD", "COMPANY", 2L, "海豚", "MODEL", "关联车型"),
                relationRow(2L, 1L, "BYD", "COMPANY", 3L, "12万辆", "SALES", "销量表现"),
                relationRow(3L, 10L, "旧报告", "REPORT", 1L, "BYD", "COMPANY", "来源提及"),
                relationRow(4L, 11L, "LTPS", "TECHNOLOGY", 12L, "2023 Q3", "PERIOD", "发生时间")));

        Map<String, Object> graph = new KnowledgeGraphService(mapper).graph("", "", null, List.of(), true, 100);
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");
        List<Map<String, Object>> links = (List<Map<String, Object>>) graph.get("links");
        List<Map<String, Object>> categories = (List<Map<String, Object>>) graph.get("categories");

        Set<String> types = nodes.stream().map(n -> String.valueOf(n.get("entityType"))).collect(Collectors.toSet());
        assertEquals(Set.of("COMPANY", "MODEL", "SALES"), types);
        assertEquals(2, links.size());
        assertFalse(nodes.stream().anyMatch(n -> "REPORT".equals(n.get("entityType"))));
        assertFalse(nodes.stream().anyMatch(n -> "TECHNOLOGY".equals(n.get("entityType"))));
        assertFalse(nodes.stream().anyMatch(n -> "PERIOD".equals(n.get("entityType"))));
        assertEquals(Set.of("企业", "车型", "销量"),
            categories.stream().map(c -> String.valueOf(c.get("name"))).collect(Collectors.toSet()));
    }

    @Test
    void indexesOnlyCompanyModelSalesNewsFinancialPolicy()
    {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        AtomicLong ids = new AtomicLong(1);
        List<KnowledgeGraphNode> nodes = new ArrayList<>();
        List<KnowledgeGraphRelation> relations = new ArrayList<>();
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

        KnowledgeBase source = new KnowledgeBase();
        source.setId(10L);
        source.setSourceName("车市新闻");
        source.setSourceType("NEWS");
        KnowledgeVersion version = new KnowledgeVersion();
        version.setId(20L);
        version.setOriginalName("2023Q3车市快讯");
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(30L);
        chunk.setMetricId("market.oem_rank");
        chunk.setContent("2023年Q3新闻：企业：比亚迪，车型：海豚，销量达到12万辆；"
            + "一季度财报显示增长，相关依据为《新能源汽车产业发展政策》。采用LTPS技术，客户：某主机厂。");

        new KnowledgeGraphService(mapper).indexChunk(source, version, chunk);

        Set<String> types = nodes.stream().map(KnowledgeGraphNode::getEntityType).collect(Collectors.toSet());
        assertTrue(types.containsAll(Set.of("COMPANY", "MODEL", "SALES", "NEWS", "FINANCIAL", "POLICY")));
        assertFalse(types.contains("TECHNOLOGY"));
        assertFalse(types.contains("CUSTOMER"));
        assertFalse(types.contains("METRIC"));
        assertFalse(types.contains("PERIOD"));
        assertFalse(types.contains("DOCUMENT"));
        assertTrue(relations.size() >= 5);
    }

    private Map<String, Object> relationRow(Long relationId, Long fromId, String fromName, String fromType,
        Long toId, String toName, String toType, String relationType)
    {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("relationId", relationId);
        row.put("relationType", relationType);
        row.put("sourceId", 100L);
        row.put("fromId", fromId);
        row.put("fromName", fromName);
        row.put("fromType", fromType);
        row.put("toId", toId);
        row.put("toName", toName);
        row.put("toType", toType);
        row.put("chunkId", 1000L);
        row.put("sourceName", "测试来源");
        row.put("evidenceSnippet", toName);
        return row;
    }
}
