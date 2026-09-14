package com.ruoyi.business.knowledge.service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.nio.file.Path;
import com.ruoyi.business.knowledge.domain.KnowledgeBase;
import com.ruoyi.business.knowledge.domain.KnowledgeChunk;
import com.ruoyi.business.knowledge.domain.KnowledgeGraphNode;
import com.ruoyi.business.knowledge.domain.KnowledgeGraphRelation;
import com.ruoyi.business.knowledge.domain.KnowledgeVersion;
import com.ruoyi.business.knowledge.mapper.KnowledgeBaseMapper;
import org.springframework.stereotype.Service;

/** 从知识切片生成可溯源的实体关系，并组装前端力导向图。 */
@Service
public class KnowledgeGraphService
{
    private static final Pattern PERIOD = Pattern.compile("(?i)(20\\d{2})\\s*(?:年\\s*)?(Q[1-4]|第?[一二三四1234]季度)?");
    private static final Pattern SALES = Pattern.compile("(?:销量|出货量|Shipment)\\s*(?:为|达到|约|[:：=])?\\s*([-+]?\\d[\\d,.]*\\s*(?:万辆|万台|辆|台|Kpcs|Mpcs|pcs))", Pattern.CASE_INSENSITIVE);
    private static final Pattern FINANCIAL = Pattern.compile("(?:20\\d{2}年)?(?:第?[一二三四1234]季度|Q[1-4])?\\s*(?:财报|业绩报告|年度报告|年报)|(?:financial|quarterly|annual)\\s+report", Pattern.CASE_INSENSITIVE);
    private static final Pattern POLICY = Pattern.compile("《[^》]{2,60}(?:政策|办法|条例|通知|规划)[^》]*》|(?:新能源|汽车|车载显示|智能网联)[^。；\\n]{0,20}(?:政策|办法|规划|通知)|\\b(?:policy|regulation)\\b", Pattern.CASE_INSENSITIVE);
    private static final Map<String, List<String>> COMPANIES = companyAliases();
    private static final List<String> MODELS = List.of("海豚", "秦PLUS", "宋PLUS", "元PLUS", "汉", "唐", "腾势D9",
        "Model 3", "Model Y", "DM-i", "HUD", "SUV", "EV");
    private static final Map<String, List<String>> TECHNOLOGIES = Map.of(
        "LTPS", List.of("LTPS"), "a-Si", List.of("a-Si", "aSi"),
        "OLED", List.of("OLED"), "Oxide", List.of("Oxide"));
    private static final Map<String, List<String>> APPLICATIONS = Map.of(
        "仪表", List.of("仪表", "Instrument cluster"), "中控", List.of("中控", "Center stack display"),
        "HUD", List.of("HUD", "Head-up display"), "控制屏", List.of("控制屏", "Control panel"),
        "后视镜", List.of("后视镜", "Room mirror", "Side mirror"),
        "娱乐屏", List.of("娱乐屏", "Passenger display"));
    private static final int MAX_ENTITIES_PER_CHUNK = 24;

    private final KnowledgeBaseMapper mapper;
    private final KnowledgeFileStorage fileStorage;

    public KnowledgeGraphService(KnowledgeBaseMapper mapper)
    {
        this(mapper, null);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public KnowledgeGraphService(KnowledgeBaseMapper mapper, KnowledgeFileStorage fileStorage)
    {
        this.mapper = mapper;
        this.fileStorage = fileStorage;
    }

    public void clearVersion(Long versionId)
    {
        mapper.deleteGraphRelationsByVersionId(versionId);
    }

    public void indexChunk(KnowledgeBase source, KnowledgeVersion version, KnowledgeChunk chunk)
    {
        List<EntityValue> entities = extract(source, version, chunk);
        if (entities.size() < 2) return;
        List<KnowledgeGraphNode> nodes = new ArrayList<>();
        for (EntityValue entity : entities)
        {
            KnowledgeGraphNode node = new KnowledgeGraphNode();
            node.setEntityName(entity.name()); node.setEntityType(entity.type());
            node.setEntityKey(entity.type() + ":" + normalize(entity.name())); node.setAliases(entity.aliases());
            mapper.upsertGraphNode(node);
            nodes.add(node);
        }
        String period = detectPeriod(chunk.getContent());
        KnowledgeGraphNode document = nodes.get(0);
        for (int i = 1; i < nodes.size(); i++)
            insertRelation(document, nodes.get(i), "来源提及", source, version, chunk, period);
        List<KnowledgeGraphNode> companies = nodes.stream().filter(n -> "COMPANY".equals(n.getEntityType())).toList();
        for (KnowledgeGraphNode company : companies)
            for (KnowledgeGraphNode target : nodes)
                if (!target.getId().equals(company.getId()) && target != document)
                    insertRelation(company, target, relationFor(target.getEntityType()), source, version, chunk, period);
    }

    public Map<String, Object> graph(String period, String dataType, Long centerId,
        List<Long> roleIds, boolean admin, int limit)
    {
        List<Map<String, Object>> rows = mapper.selectGraphRelations(cleanPeriod(period), cleanType(dataType), centerId,
            safeRoles(roleIds), admin, Math.max(1, Math.min(limit, 500)));
        return assemble(rows, centerId);
    }

    public Map<String, Object> rebuildCurrentGraph()
    {
        final int rebuildLimit = 20000;
        List<KnowledgeChunk> chunks = mapper.selectCurrentChunks(rebuildLimit);
        Set<Long> cleared = new LinkedHashSet<>();
        int indexed = 0;
        for (KnowledgeChunk chunk : chunks)
        {
            if (cleared.add(chunk.getVersionId())) clearVersion(chunk.getVersionId());
            KnowledgeBase source = new KnowledgeBase();
            source.setId(chunk.getSourceId()); source.setSourceName(chunk.getSourceName()); source.setSourceType(chunk.getSourceType());
            KnowledgeVersion version = new KnowledgeVersion();
            version.setId(chunk.getVersionId()); version.setVersionNo(chunk.getVersionNo()); version.setOriginalName(chunk.getOriginalName());
            indexChunk(source, version, chunk); indexed++;
        }
        return Map.of("chunkCount", indexed, "versionCount", cleared.size(), "truncated", chunks.size() >= rebuildLimit);
    }

    public Map<String, Object> graphForChunks(List<KnowledgeChunk> chunks, List<Long> roleIds, boolean admin)
    {
        List<Long> ids = chunks == null ? List.of() : chunks.stream().map(KnowledgeChunk::getId)
            .filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return emptyGraph(null);
        return assemble(mapper.selectGraphRelationsByChunkIds(ids, safeRoles(roleIds), admin, 300), null);
    }

    public Map<String, Object> evidence(Long chunkId, Integer startOffset, Integer endOffset,
        List<Long> roleIds, boolean admin)
    {
        KnowledgeChunk chunk = mapper.selectAuthorizedChunkById(chunkId, safeRoles(roleIds), admin);
        if (chunk == null) throw new IllegalArgumentException("原文证据不存在或无权访问");
        String content = chunk.getContent() == null ? "" : chunk.getContent();
        int start = Math.max(0, Math.min(startOffset == null ? 0 : startOffset, content.length()));
        int end = Math.max(start, Math.min(endOffset == null ? content.length() : endOffset, content.length()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("chunkId", chunk.getId()); result.put("sourceName", chunk.getSourceName());
        result.put("originalName", chunk.getOriginalName()); result.put("versionNo", chunk.getVersionNo());
        result.put("sourceType", chunk.getSourceType());
        result.put("pageStart", chunk.getPageStart()); result.put("pageEnd", chunk.getPageEnd());
        result.put("sourceUrl", chunk.getSourceUrl()); result.put("reportId", chunk.getReportId());
        result.put("metricId", chunk.getMetricId()); result.put("content", content);
        result.put("startOffset", start); result.put("endOffset", end);
        result.put("highlightedText", content.substring(start, end));
        result.put("fileAvailable", isPdfFileAvailable(chunk));
        return result;
    }

    public SourceFile sourceFile(Long chunkId, List<Long> roleIds, boolean admin)
    {
        if (fileStorage == null) throw new IllegalStateException("知识库文件存储服务不可用");
        KnowledgeChunk chunk = mapper.selectAuthorizedChunkById(chunkId, safeRoles(roleIds), admin);
        if (chunk == null) throw new IllegalArgumentException("原始文件不存在或无权访问");
        KnowledgeVersion version = mapper.selectVersionById(chunk.getVersionId());
        if (version == null || !"PDF".equalsIgnoreCase(chunk.getSourceType()))
            throw new IllegalArgumentException("该引用没有可预览的PDF原件");
        try
        {
            Path path = fileStorage.resolveForRead(version.getStoredPath());
            return new SourceFile(path, safeFileName(version.getOriginalName()));
        }
        catch (java.io.IOException e)
        {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    private boolean isPdfFileAvailable(KnowledgeChunk chunk)
    {
        if (fileStorage == null || chunk == null || !"PDF".equalsIgnoreCase(chunk.getSourceType())) return false;
        KnowledgeVersion version = mapper.selectVersionById(chunk.getVersionId());
        if (version == null) return false;
        try { fileStorage.resolveForRead(version.getStoredPath()); return true; }
        catch (java.io.IOException ignored) { return false; }
    }

    private String safeFileName(String value)
    {
        String name = value == null || value.isBlank() ? "knowledge-source.pdf" : value;
        return name.replace("\r", "").replace("\n", "").replace("\"", "'");
    }

    public record SourceFile(Path path, String originalName) { }

    private void insertRelation(KnowledgeGraphNode from, KnowledgeGraphNode to, String type,
        KnowledgeBase source, KnowledgeVersion version, KnowledgeChunk chunk, String period)
    {
        if (from.getId() == null || to.getId() == null || from.getId().equals(to.getId())) return;
        KnowledgeGraphRelation relation = new KnowledgeGraphRelation();
        relation.setFromEntityId(from.getId()); relation.setToEntityId(to.getId()); relation.setRelationType(type);
        relation.setSourceId(source.getId()); relation.setVersionId(version.getId()); relation.setChunkId(chunk.getId());
        EvidenceWindow evidence = evidenceWindow(chunk.getContent(), to.getEntityName());
        relation.setPeriod(period); relation.setDataType(source.getSourceType()); relation.setEvidenceSnippet(evidence.text());
        relation.setEvidenceStart(evidence.start()); relation.setEvidenceEnd(evidence.end());
        mapper.insertGraphRelation(relation);
    }

    private List<EntityValue> extract(KnowledgeBase source, KnowledgeVersion version, KnowledgeChunk chunk)
    {
        String text = chunk.getContent() == null ? "" : chunk.getContent();
        LinkedHashMap<String, EntityValue> values = new LinkedHashMap<>();
        String documentType = switch (source.getSourceType().toUpperCase(Locale.ROOT)) {
            case "NEWS" -> "NEWS";
            case "POLICY" -> "POLICY";
            case "REPORT" -> text.contains("财报") || source.getSourceName().contains("财报") ? "FINANCIAL" : "REPORT";
            default -> "DOCUMENT";
        };
        add(values, source.getSourceName(), documentType, version.getOriginalName());
        for (Map.Entry<String, List<String>> entry : COMPANIES.entrySet())
            if (entry.getValue().stream().anyMatch(alias -> containsIgnoreCase(text, alias)))
                add(values, entry.getKey(), "COMPANY", String.join(",", entry.getValue()));
        for (String model : MODELS) if (containsIgnoreCase(text, model)) add(values, model, "MODEL", "");
        for (Map.Entry<String, List<String>> entry : TECHNOLOGIES.entrySet())
            if (entry.getValue().stream().anyMatch(alias -> containsIgnoreCase(text, alias)))
                add(values, entry.getKey(), "TECHNOLOGY", String.join(",", entry.getValue()));
        for (Map.Entry<String, List<String>> entry : APPLICATIONS.entrySet())
            if (entry.getValue().stream().anyMatch(alias -> containsIgnoreCase(text, alias)))
                add(values, entry.getKey(), "APPLICATION", String.join(",", entry.getValue()));
        extractKeywordValues(values, text, "企业|公司|车企", "COMPANY");
        extractKeywordValues(values, text, "车型|车系", "MODEL");
        extractKeywordValues(values, text, "客户|Client", "CUSTOMER");
        if (chunk.getMetricId() != null && !chunk.getMetricId().isBlank())
            add(values, chunk.getMetricId(), "METRIC", "");
        String period = detectPeriod(text);
        if (!period.isBlank()) add(values, period, "PERIOD", "");
        extractMatches(values, SALES, text, "SALES", 1);
        extractMatches(values, FINANCIAL, text, "FINANCIAL", 0);
        extractMatches(values, POLICY, text, "POLICY", 0);
        return values.values().stream().limit(MAX_ENTITIES_PER_CHUNK).toList();
    }

    private void extractKeywordValues(Map<String, EntityValue> values, String text, String keyword, String type)
    {
        Pattern pattern = Pattern.compile("(?:" + keyword + ")\\s*[:：=]\\s*([^，,。；;\\n]{1,30})");
        Matcher matcher = pattern.matcher(text);
        while (matcher.find())
            for (String item : matcher.group(1).split("[/、和与]")) add(values, item.trim(), type, "");
    }

    private void extractMatches(Map<String, EntityValue> values, Pattern pattern, String text, String type, int group)
    {
        Matcher matcher = pattern.matcher(text);
        while (matcher.find()) add(values, matcher.group(group).trim(), type, "");
    }

    private void add(Map<String, EntityValue> values, String name, String type, String aliases)
    {
        if (name == null) return;
        String clean = name.trim().replaceAll("^[：:=,，]+|[：:=,，]+$", "");
        if (clean.length() < 2 || clean.length() > 80) return;
        values.putIfAbsent(type + ":" + normalize(clean), new EntityValue(clean, type, aliases == null ? "" : aliases));
    }

    private Map<String, Object> assemble(List<Map<String, Object>> rows, Long centerId)
    {
        if (rows == null || rows.isEmpty()) return emptyGraph(centerId);
        Map<Long, Map<String, Object>> nodes = new LinkedHashMap<>();
        Map<String, Map<String, Object>> links = new LinkedHashMap<>();
        Map<String, Set<String>> linkSources = new LinkedHashMap<>();
        for (Map<String, Object> row : rows)
        {
            Long fromId = longValue(row.get("fromId")); Long toId = longValue(row.get("toId"));
            addViewNode(nodes, fromId, string(row.get("fromName")), string(row.get("fromType")), centerId);
            addViewNode(nodes, toId, string(row.get("toName")), string(row.get("toType")), centerId);
            String key = fromId + ":" + toId + ":" + row.get("relationType");
            Map<String, Object> link = links.computeIfAbsent(key, ignored -> newLink(row, fromId, toId));
            int mentionCount = ((Number) link.get("mentionCount")).intValue() + 1;
            link.put("mentionCount", mentionCount);
            Set<String> sources = linkSources.computeIfAbsent(key, ignored -> new LinkedHashSet<>());
            if (row.get("sourceId") != null) sources.add(String.valueOf(row.get("sourceId")));
            link.put("sourceCount", sources.size());
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> evidences = (List<Map<String, Object>>) link.get("evidences");
            if (evidences.size() < 10) evidences.add(evidence(row));
        }
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("nodes", new ArrayList<>(nodes.values())); graph.put("links", new ArrayList<>(links.values()));
        graph.put("categories", categories(nodes.values())); graph.put("centerId", centerId);
        return graph;
    }

    private Map<String, Object> newLink(Map<String, Object> row, Long fromId, Long toId)
    {
        Map<String, Object> link = new LinkedHashMap<>();
        link.put("id", row.get("relationId")); link.put("source", String.valueOf(fromId));
        link.put("target", String.valueOf(toId)); link.put("name", row.get("relationType"));
        link.put("period", row.get("period")); link.put("dataType", row.get("dataType"));
        link.put("chunkId", row.get("chunkId")); link.put("sourceName", row.get("sourceName"));
        link.put("versionNo", row.get("versionNo")); link.put("originalName", row.get("originalName"));
        link.put("pageStart", row.get("pageStart")); link.put("pageEnd", row.get("pageEnd"));
        link.put("sourceUrl", row.get("sourceUrl")); link.put("evidenceSnippet", row.get("evidenceSnippet"));
        link.put("startOffset", row.get("startOffset")); link.put("endOffset", row.get("endOffset"));
        link.put("mentionCount", 0); link.put("sourceCount", 0); link.put("evidences", new ArrayList<>());
        return link;
    }

    private Map<String, Object> evidence(Map<String, Object> row)
    {
        Map<String, Object> evidence = new LinkedHashMap<>();
        for (String field : List.of("relationId", "sourceId", "chunkId", "sourceName", "versionNo", "originalName",
            "pageStart", "pageEnd", "sourceUrl", "evidenceSnippet", "startOffset", "endOffset", "period", "dataType"))
            evidence.put(field, row.get(field));
        return evidence;
    }

    private void addViewNode(Map<Long, Map<String, Object>> nodes, Long id, String name, String type, Long centerId)
    {
        if (id == null) return;
        nodes.computeIfAbsent(id, ignored -> {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", String.valueOf(id)); node.put("name", name); node.put("entityType", type);
            node.put("category", categoryIndex(type)); node.put("symbolSize", id.equals(centerId) ? 54 : nodeSize(type));
            node.put("draggable", true); return node;
        });
    }

    private List<Map<String, Object>> categories(Collection<Map<String, Object>> nodes)
    {
        List<String> order = List.of("COMPANY", "MODEL", "SALES", "NEWS", "FINANCIAL", "POLICY", "REPORT", "DOCUMENT",
            "TECHNOLOGY", "APPLICATION", "CUSTOMER", "PERIOD", "METRIC");
        List<Map<String, Object>> result = new ArrayList<>();
        for (String type : order) result.add(Map.of("name", typeLabel(type), "entityType", type));
        return result;
    }

    private Map<String, Object> emptyGraph(Long centerId)
    {
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("nodes", List.of()); graph.put("links", List.of()); graph.put("categories", List.of()); graph.put("centerId", centerId);
        return graph;
    }

    private int categoryIndex(String type)
    {
        return switch (type) { case "COMPANY" -> 0; case "MODEL" -> 1; case "SALES" -> 2; case "NEWS" -> 3;
            case "FINANCIAL" -> 4; case "POLICY" -> 5; case "REPORT" -> 6; case "TECHNOLOGY" -> 8;
            case "APPLICATION" -> 9; case "CUSTOMER" -> 10; case "PERIOD" -> 11; case "METRIC" -> 12; default -> 7; };
    }

    private int nodeSize(String type) { return "COMPANY".equals(type) ? 42 : ("DOCUMENT".equals(type) ? 26 : 34); }
    private String typeLabel(String type) { return switch (type) { case "COMPANY" -> "企业"; case "MODEL" -> "车型";
        case "SALES" -> "销量"; case "NEWS" -> "新闻"; case "FINANCIAL" -> "财报"; case "POLICY" -> "政策";
        case "REPORT" -> "分析报告"; case "TECHNOLOGY" -> "技术"; case "APPLICATION" -> "应用";
        case "CUSTOMER" -> "客户"; case "PERIOD" -> "时间"; case "METRIC" -> "指标"; default -> "资料"; }; }
    private String relationFor(String type) { return switch (type) { case "MODEL" -> "关联车型"; case "SALES" -> "销量表现";
        case "NEWS" -> "相关新闻"; case "FINANCIAL" -> "披露财报"; case "POLICY" -> "关联政策";
        case "TECHNOLOGY" -> "采用技术"; case "APPLICATION" -> "关联应用"; case "CUSTOMER" -> "关联客户";
        case "PERIOD" -> "发生时间"; case "METRIC" -> "关联指标"; default -> "关联"; }; }
    private String detectPeriod(String text) { Matcher matcher = PERIOD.matcher(text == null ? "" : text); if (!matcher.find()) return "";
        String quarter = matcher.group(2); return matcher.group(1) + (quarter == null ? "" : " " + normalizeQuarter(quarter)); }
    private String normalizeQuarter(String value) { if (value == null) return ""; String q=value.toUpperCase(Locale.ROOT).replace("第", "").replace("季度", "");
        return switch (q) { case "一", "1" -> "Q1"; case "二", "2" -> "Q2"; case "三", "3" -> "Q3"; case "四", "4" -> "Q4"; default -> q; }; }
    private String cleanPeriod(String value) { return value == null ? "" : value.trim(); }
    private String cleanType(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private List<Long> safeRoles(List<Long> roles) { return roles == null ? List.of() : roles; }
    private String normalize(String value) { return value.toLowerCase(Locale.ROOT).replaceAll("[\\s·._—–-]+", ""); }
    private EvidenceWindow evidenceWindow(String content, String entity)
    {
        if (content == null || content.isEmpty()) return new EvidenceWindow("", 0, 0);
        int index = content.toLowerCase(Locale.ROOT).indexOf(entity == null ? "" : entity.toLowerCase(Locale.ROOT));
        if (index < 0) index = 0;
        int start = Math.max(0, index - 120); int end = Math.min(content.length(), index + Math.max(1, entity == null ? 0 : entity.length()) + 260);
        return new EvidenceWindow(content.substring(start, end), start, end);
    }
    private boolean containsIgnoreCase(String text, String value) { return text.toLowerCase(Locale.ROOT).contains(value.toLowerCase(Locale.ROOT)); }
    private String string(Object value) { return value == null ? "" : String.valueOf(value); }
    private Long longValue(Object value) { return value == null ? null : (value instanceof Number n ? n.longValue() : Long.valueOf(String.valueOf(value))); }

    private static Map<String, List<String>> companyAliases()
    {
        Map<String, List<String>> values = new LinkedHashMap<>();
        values.put("Tianma", List.of("Tianma", "天马")); values.put("AUO", List.of("AUO", "友达"));
        values.put("BOE", List.of("BOE", "京东方")); values.put("CSOT", List.of("CSOT", "China Star", "华星"));
        values.put("BYD", List.of("BYD", "比亚迪")); values.put("Tesla", List.of("Tesla", "特斯拉"));
        values.put("XPeng", List.of("XPeng", "小鹏")); values.put("NIO", List.of("NIO", "蔚来"));
        values.put("Li Auto", List.of("Li Auto", "理想汽车", "理想"));
        values.put("SAIC", List.of("SAIC", "上汽")); values.put("GAC", List.of("GAC", "广汽"));
        values.put("Changan", List.of("Changan", "长安汽车", "长安"));
        values.put("Great Wall", List.of("Great Wall", "长城汽车", "长城"));
        values.put("BMW", List.of("BMW", "宝马")); values.put("Mercedes-Benz", List.of("Mercedes-Benz", "奔驰"));
        values.put("Volkswagen", List.of("Volkswagen", "大众")); values.put("Geely", List.of("Geely", "吉利"));
        return values;
    }

    private record EntityValue(String name, String type, String aliases) { }
    private record EvidenceWindow(String text, int start, int end) { }
}
