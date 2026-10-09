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
    private static final Map<String, List<String>> BASE_COMPANIES = companyAliases();
    private static final List<String> BASE_MODELS = List.of(
        // 比亚迪海洋/王朝及年报常见具体型号（长名优先，便于命中「海狮06」等写法）
        "海狮05EV", "海狮07DM-i", "海狮06", "海狮07", "海狮05", "海狮",
        "海豹06DM", "海豹06", "海豹", "海豚", "海鸥",
        "秦PLUS", "秦L EV", "秦L", "宋Pro DM", "宋Pro", "宋PLUS",
        "元PLUS", "元UP", "汉L", "唐L", "汉", "唐", "夏", "大唐EV", "DM-i",
        "腾势N8L", "腾势N9", "腾势N7", "腾势D9", "腾势",
        "仰望U9", "仰望U8", "仰望U7", "仰望",
        "方程豹", "豹8", "豹5",
        "Model 3", "Model Y", "iCAR", "风云", "星途", "捷途", "瑶光", "艾瑞泽", "艾瑞泽8",
        "UNI-V", "UNI-K", "UNI-T", "CS75", "CS55", "逸动", "深蓝", "阿维塔", "理想L9", "理想L8", "理想L7",
        "R7", "S7", "V23", "ET5", "ET7", "ES6", "ES8");
    /**
     * 仅抽取「产品形态」车型名：含字母/数字的型号（UNI-V、唐L、海狮05EV），
     * 或出现在旗下/品牌后的已知系列短名。禁止把「向上/突破/安全」等叙述词当车型。
     */
    private static final Pattern MODEL_PRODUCT = Pattern.compile(
        "(?:旗下|品牌|车型|车系|系列|推出|上市)\\s*[:：为是]?\\s*"
            + "([A-Za-z][A-Za-z0-9+\\-]{1,20}|[\\u4e00-\\u9fa5]{1,6}[A-Za-z0-9][A-Za-z0-9+\\-]{0,12})");
    /** 品牌网/产品系列名，不是具体车型，禁止写成 MODEL。 */
    private static final Set<String> MODEL_BLOCKLIST = Set.of(
        "王朝", "海洋", "王朝网", "海洋网", "Dynasty", "Ocean", "产品系列", "品牌",
        // 竞争社/指标 JSON 键名，曾被误抽成车型（G06 空图根因之一）
        "formula", "values", "years", "quarters", "technologies", "makers", "maker",
        "metric_id", "metricId", "yoy", "scope", "engine", "title", "summary");
    private static final Set<String> SERIES_ALLOWLIST = Set.of(
        "风云", "星途", "捷途", "瑶光", "深蓝", "阿维塔", "海豚", "海豹", "海狮",
        "汉", "唐", "夏", "腾势", "仰望", "方程豹", "艾瑞泽", "瑞虎");
    /** 图谱只保留这六类业务节点；无边关联的节点不展示。 */
    private static final Set<String> VISIBLE_ENTITY_TYPES = Set.of(
        "COMPANY", "MODEL", "SALES", "NEWS", "FINANCIAL", "POLICY");
    private static final int MAX_ENTITIES_PER_CHUNK = 24;
    /** 同一切片多家企业时，最多建多少条「关联企业」共现边，避免全连接爆炸。 */
    private static final int MAX_COMPANY_CO_OCCURRENCE_EDGES = 12;

    private final KnowledgeBaseMapper mapper;
    private final KnowledgeFileStorage fileStorage;
    private KnowledgeVehicleCatalog vehicleCatalog;

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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    public void setVehicleCatalog(KnowledgeVehicleCatalog vehicleCatalog)
    {
        this.vehicleCatalog = vehicleCatalog;
    }

    public void clearVersion(Long versionId)
    {
        mapper.deleteGraphRelationsByVersionId(versionId);
    }

    /** 问答提议写入：按 type:normalize(name) 幂等 upsert；企业/车型走中英别名归一。 */
    public KnowledgeGraphNode upsertVisibleNode(String name, String type, String aliases)
    {
        if (name == null || name.isBlank() || type == null || type.isBlank()) return null;
        String entityType = type.trim().toUpperCase(Locale.ROOT);
        if (!VISIBLE_ENTITY_TYPES.contains(entityType)) return null;
        if ("MODEL".equals(entityType) && isBlockedModel(name)) return null;
        String entityName = name.trim();
        String aliasCsv = aliases == null ? "" : aliases;
        if ("COMPANY".equals(entityType))
        {
            entityName = resolveCompanyName(entityName);
            aliasCsv = resolveCompanyAliases(entityName);
        }
        else if ("MODEL".equals(entityType))
        {
            entityName = resolveModelName(entityName);
            if (isBlockedModel(entityName)) return null;
        }
        KnowledgeGraphNode node = new KnowledgeGraphNode();
        node.setEntityName(entityName);
        node.setEntityType(entityType);
        node.setEntityKey(entityType + ":" + normalize(entityName));
        node.setAliases(aliasCsv);
        mapper.upsertGraphNode(node);
        return node;
    }

    /** 问答提议写入：带证据偏移的关系，insert ignore 幂等。 */
    public void insertProposedRelation(KnowledgeGraphNode from, KnowledgeGraphNode to, String relationType,
        KnowledgeChunk chunk, String evidenceSnippet, int evidenceStart, int evidenceEnd, String period)
    {
        if (from == null || to == null || from.getId() == null || to.getId() == null) return;
        if (from.getId().equals(to.getId())) return;
        if (chunk == null || chunk.getId() == null) return;
        KnowledgeGraphRelation relation = new KnowledgeGraphRelation();
        relation.setFromEntityId(from.getId());
        relation.setToEntityId(to.getId());
        relation.setRelationType(relationType);
        relation.setSourceId(chunk.getSourceId());
        relation.setVersionId(chunk.getVersionId());
        relation.setChunkId(chunk.getId());
        relation.setPeriod(period == null ? "" : period);
        relation.setDataType(chunk.getSourceType());
        relation.setEvidenceSnippet(evidenceSnippet == null ? "" : evidenceSnippet);
        relation.setEvidenceStart(Math.max(0, evidenceStart));
        relation.setEvidenceEnd(Math.max(evidenceStart, evidenceEnd));
        mapper.insertGraphRelation(relation);
    }

    public void indexChunk(KnowledgeBase source, KnowledgeVersion version, KnowledgeChunk chunk)
    {
        List<EntityValue> entities = extract(source, version, chunk).stream()
            .filter(entity -> VISIBLE_ENTITY_TYPES.contains(entity.type()))
            .toList();
        if (entities.size() < 2) return;
        List<KnowledgeGraphNode> nodes = new ArrayList<>();
        for (EntityValue entity : entities)
        {
            KnowledgeGraphNode node = upsertVisibleNode(entity.name(), entity.type(), entity.aliases());
            if (node != null && node.getId() != null) nodes.add(node);
        }
        if (nodes.size() < 2) return;
        String period = detectPeriod(chunk.getContent());
        List<KnowledgeGraphNode> companies = nodes.stream().filter(n -> "COMPANY".equals(n.getEntityType())).toList();
        List<KnowledgeGraphNode> sources = nodes.stream()
            .filter(n -> Set.of("NEWS", "FINANCIAL", "POLICY").contains(n.getEntityType())).toList();
        for (KnowledgeGraphNode document : sources)
            for (KnowledgeGraphNode target : nodes)
                if (!target.getId().equals(document.getId()))
                    insertRelation(document, target, "来源提及", source, version, chunk, period);
        String content = chunk.getContent() == null ? "" : chunk.getContent();
        boolean singleCompany = companies.size() == 1;
        for (KnowledgeGraphNode company : companies)
            for (KnowledgeGraphNode target : nodes)
                if (!target.getId().equals(company.getId())
                    && !Set.of("NEWS", "FINANCIAL", "POLICY", "COMPANY").contains(target.getEntityType())
                    && (singleCompany || nearInText(content, companyNames(company), target.getEntityName())))
                    insertRelation(company, target, relationFor(target.getEntityType()), source, version, chunk, period);
        // 多厂商同片（竞争社 Summary 等）：补 COMPANY↔COMPANY 共现边，避免「有实体无边」导致问答图谱为空
        if (companies.size() >= 2)
        {
            int coEdges = 0;
            for (int i = 0; i < companies.size() && coEdges < MAX_COMPANY_CO_OCCURRENCE_EDGES; i++)
            {
                for (int j = i + 1; j < companies.size() && coEdges < MAX_COMPANY_CO_OCCURRENCE_EDGES; j++)
                {
                    insertRelation(companies.get(i), companies.get(j), "关联企业", source, version, chunk, period);
                    coEdges++;
                }
            }
        }
        if (companies.isEmpty() && sources.isEmpty())
        {
            KnowledgeGraphNode anchor = nodes.get(0);
            for (int i = 1; i < nodes.size(); i++)
                insertRelation(anchor, nodes.get(i), relationFor(nodes.get(i).getEntityType()),
                    source, version, chunk, period);
        }
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
        return graphForChunks(chunks, roleIds, admin, List.of());
    }

    /** 问答图谱：只保留提问点名的车企及其直接关联节点，避免周报一块里多家企业挤在一张图。 */
    public Map<String, Object> graphForChunks(List<KnowledgeChunk> chunks, List<Long> roleIds, boolean admin,
        Collection<String> focusTerms)
    {
        List<Long> ids = chunks == null ? List.of() : chunks.stream().map(KnowledgeChunk::getId)
            .filter(java.util.Objects::nonNull).distinct().toList();
        if (ids.isEmpty()) return emptyGraph(null);
        Map<String, Object> graph = focusOnAskedCompanies(
            assemble(mapper.selectGraphRelationsByChunkIds(ids, safeRoles(roleIds), admin, 300), null),
            focusTerms);
        if (graphHasNodes(graph)) return graph;
        // citation 切片无边时：按提问实体名 + 引用资料 sourceId 回退，避免答对图空（G06）
        List<String> entityNames = expandFocusTerms(focusTerms).stream()
            .filter(name -> name != null && !name.isBlank())
            .distinct()
            .limit(24)
            .toList();
        // 同时带上规范企业名（Tianma/BOE），避免 normalize 后查库对不上
        LinkedHashSet<String> lookupNames = new LinkedHashSet<>(entityNames);
        for (String term : focusTerms == null ? List.<String>of() : focusTerms)
        {
            if (term == null || term.isBlank()) continue;
            lookupNames.add(term.trim());
            for (Map.Entry<String, List<String>> entry : activeCompanies().entrySet())
            {
                boolean hit = containsIgnoreCase(entry.getKey(), term)
                    || entry.getValue().stream().anyMatch(alias -> containsIgnoreCase(alias, term)
                        || containsIgnoreCase(term, alias));
                if (!hit) continue;
                lookupNames.add(entry.getKey());
                lookupNames.addAll(entry.getValue());
            }
        }
        List<Long> sourceIds = chunks.stream().map(KnowledgeChunk::getSourceId)
            .filter(java.util.Objects::nonNull).distinct().toList();
        if (lookupNames.isEmpty() || sourceIds.isEmpty()) return graph == null ? emptyGraph(null) : graph;
        Map<String, Object> widened = focusOnAskedCompanies(
            assemble(mapper.selectGraphRelationsByEntityNames(new ArrayList<>(lookupNames), sourceIds,
                safeRoles(roleIds), admin, 300), null),
            focusTerms);
        return graphHasNodes(widened) ? widened : (graph == null ? emptyGraph(null) : graph);
    }

    private boolean graphHasNodes(Map<String, Object> graph)
    {
        if (graph == null) return false;
        Object nodes = graph.get("nodes");
        return nodes instanceof List<?> list && !list.isEmpty();
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
        result.put("fileAvailable", isOriginalFileAvailable(chunk));
        result.put("fileKind", resolveFileKind(chunk));
        return result;
    }

    public SourceFile sourceFile(Long chunkId, List<Long> roleIds, boolean admin)
    {
        if (fileStorage == null) throw new IllegalStateException("知识库文件存储服务不可用");
        KnowledgeChunk chunk = mapper.selectAuthorizedChunkById(chunkId, safeRoles(roleIds), admin);
        if (chunk == null) throw new IllegalArgumentException("原始文件不存在或无权访问");
        KnowledgeVersion version = mapper.selectVersionById(chunk.getVersionId());
        if (version == null) throw new IllegalArgumentException("该引用没有可预览的原件");
        String kind = fileKindOf(version.getOriginalName(), version.getStoredPath());
        if (!"pdf".equals(kind) && !"pptx".equals(kind))
            throw new IllegalArgumentException("该引用没有可预览的 PDF/PPTX 原件");
        try
        {
            Path path = fileStorage.resolveForRead(version.getStoredPath());
            return new SourceFile(path, safeFileName(version.getOriginalName()), kind);
        }
        catch (java.io.IOException e)
        {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /** Stream the durable stored binary for any authorized knowledge version (PDF/Office). */
    public SourceFile versionSourceFile(Long versionId, List<Long> roleIds, boolean admin)
    {
        if (fileStorage == null) throw new IllegalStateException("知识库文件存储服务不可用");
        if (versionId == null) throw new IllegalArgumentException("版本不存在或无权访问");
        KnowledgeVersion version = mapper.selectVersionById(versionId);
        if (version == null) throw new IllegalArgumentException("版本不存在或无权访问");
        KnowledgeBase source = mapper.selectAuthorizedKnowledgeBaseById(version.getSourceId(),
            safeRoles(roleIds), admin);
        if (source == null) throw new IllegalArgumentException("版本不存在或无权访问");
        try
        {
            Path path = fileStorage.resolveForRead(version.getStoredPath());
            String kind = fileKindOf(version.getOriginalName(), version.getStoredPath());
            return new SourceFile(path, safeFileName(version.getOriginalName()), kind);
        }
        catch (java.io.IOException e)
        {
            throw new IllegalArgumentException(e.getMessage(), e);
        }
    }

    /**
     * 在线定位预览：PDF 返回页码与高亮文本（前端 PDF.js 渲染）；
     * PPTX 用 POI 抽出指定幻灯片正文，供前端内嵌高亮，避免下载。
     */
    public Map<String, Object> locatePreview(Long chunkId, Integer startOffset, Integer endOffset,
        Integer slideNo, List<Long> roleIds, boolean admin)
    {
        Map<String, Object> evidence = evidence(chunkId, startOffset, endOffset, roleIds, admin);
        String kind = evidence.get("fileKind") == null ? null : String.valueOf(evidence.get("fileKind"));
        Map<String, Object> result = new LinkedHashMap<>(evidence);
        result.put("kind", kind);
        if (!Boolean.TRUE.equals(evidence.get("fileAvailable")))
            throw new IllegalArgumentException("当前知识源没有可打开的 PDF/PPTX 原件（文件缺失或上传目录已变更）");
        if ("pptx".equals(kind))
        {
            int targetSlide = slideNo != null && slideNo > 0
                ? slideNo
                : (evidence.get("pageStart") instanceof Number n ? Math.max(1, n.intValue()) : 1);
            Map<String, Object> slide = extractPptxSlide(chunkId, targetSlide, roleIds, admin);
            result.putAll(slide);
        }
        else if ("pdf".equals(kind))
        {
            result.put("pageNumber", evidence.get("pageStart") == null ? 1 : evidence.get("pageStart"));
        }
        else
        {
            throw new IllegalArgumentException("暂不支持该原件格式的在线定位预览");
        }
        return result;
    }

    private Map<String, Object> extractPptxSlide(Long chunkId, int slideNumber, List<Long> roleIds, boolean admin)
    {
        SourceFile sourceFile = sourceFile(chunkId, roleIds, admin);
        try (java.io.InputStream in = java.nio.file.Files.newInputStream(sourceFile.path());
             org.apache.poi.xslf.usermodel.XMLSlideShow show = new org.apache.poi.xslf.usermodel.XMLSlideShow(in))
        {
            List<org.apache.poi.xslf.usermodel.XSLFSlide> slides = show.getSlides();
            if (slides.isEmpty()) throw new IllegalArgumentException("PPTX 中没有可预览的幻灯片");
            int index = Math.min(Math.max(slideNumber, 1), slides.size()) - 1;
            org.apache.poi.xslf.usermodel.XSLFSlide slide = slides.get(index);
            List<String> paragraphs = new ArrayList<>();
            String title = "";
            for (org.apache.poi.xslf.usermodel.XSLFShape shape : slide.getShapes())
            {
                collectPptxShapeText(shape, paragraphs);
            }
            if (!paragraphs.isEmpty()) title = paragraphs.get(0);
            Map<String, Object> slideView = new LinkedHashMap<>();
            slideView.put("slideNumber", index + 1);
            slideView.put("slideCount", slides.size());
            slideView.put("slideTitle", title);
            slideView.put("paragraphs", paragraphs);
            slideView.put("fullText", String.join("\n", paragraphs));
            return slideView;
        }
        catch (java.io.IOException e)
        {
            throw new IllegalArgumentException("读取 PPTX 幻灯片失败：" + e.getMessage(), e);
        }
    }

    private void collectPptxShapeText(org.apache.poi.xslf.usermodel.XSLFShape shape, List<String> paragraphs)
    {
        if (shape instanceof org.apache.poi.xslf.usermodel.XSLFGroupShape group)
        {
            for (org.apache.poi.xslf.usermodel.XSLFShape child : group.getShapes())
                collectPptxShapeText(child, paragraphs);
            return;
        }
        if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTable table)
        {
            for (org.apache.poi.xslf.usermodel.XSLFTableRow row : table.getRows())
            {
                List<String> cells = new ArrayList<>();
                for (org.apache.poi.xslf.usermodel.XSLFTableCell cell : row.getCells())
                {
                    String cellText = cell == null || cell.getText() == null ? "" : cell.getText().trim();
                    if (!cellText.isBlank()) cells.add(cellText);
                }
                if (!cells.isEmpty()) paragraphs.add(String.join(" | ", cells));
            }
            return;
        }
        if (shape instanceof org.apache.poi.xslf.usermodel.XSLFTextShape textShape)
        {
            String text = textShape.getText() == null ? "" : textShape.getText().trim();
            if (text.isBlank() || text.matches("\\d{1,3}")) return;
            for (String line : text.split("\\R"))
            {
                String trimmed = line == null ? "" : line.trim();
                if (!trimmed.isBlank()) paragraphs.add(trimmed);
            }
        }
    }

    private boolean isOriginalFileAvailable(KnowledgeChunk chunk)
    {
        if (fileStorage == null || chunk == null) return false;
        KnowledgeVersion version = mapper.selectVersionById(chunk.getVersionId());
        if (version == null) return false;
        String kind = fileKindOf(version.getOriginalName(), version.getStoredPath());
        if (!"pdf".equals(kind) && !"pptx".equals(kind)) return false;
        try { fileStorage.resolveForRead(version.getStoredPath()); return true; }
        catch (java.io.IOException ignored) { return false; }
    }

    private String resolveFileKind(KnowledgeChunk chunk)
    {
        if (chunk == null) return null;
        KnowledgeVersion version = mapper.selectVersionById(chunk.getVersionId());
        if (version == null) return null;
        return fileKindOf(version.getOriginalName(), version.getStoredPath());
    }

    private String fileKindOf(String originalName, String storedPath)
    {
        String probe = firstNonBlank(originalName, storedPath, "");
        String lower = probe.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".pdf")) return "pdf";
        if (lower.endsWith(".pptx")) return "pptx";
        if (lower.endsWith(".docx")) return "docx";
        if (lower.endsWith(".xlsx")) return "xlsx";
        return "other";
    }

    private String firstNonBlank(String... values)
    {
        if (values == null) return null;
        for (String value : values)
        {
            if (value != null && !value.isBlank()) return value;
        }
        return null;
    }

    private String safeFileName(String value)
    {
        String name = value == null || value.isBlank() ? "knowledge-source.bin" : value;
        return name.replace("\r", "").replace("\n", "").replace("\"", "'");
    }

    public record SourceFile(Path path, String originalName, String kind) { }

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
        String sourceType = source.getSourceType() == null ? "" : source.getSourceType().toUpperCase(Locale.ROOT);
        if ("NEWS".equals(sourceType))
            add(values, source.getSourceName(), "NEWS", version.getOriginalName());
        else if ("POLICY".equals(sourceType))
            add(values, source.getSourceName(), "POLICY", version.getOriginalName());
        else if (("REPORT".equals(sourceType) || "PDF".equals(sourceType))
            && (containsIgnoreCase(text, "财报") || containsIgnoreCase(safe(source.getSourceName()), "财报")
                || containsIgnoreCase(safe(version.getOriginalName()), "财报")
                || containsIgnoreCase(safe(source.getSourceName()), "年报")
                || containsIgnoreCase(safe(version.getOriginalName()), "年报")
                || containsIgnoreCase(safe(version.getOriginalName()), "业绩")))
            add(values, firstNonBlank(version.getOriginalName(), source.getSourceName()), "FINANCIAL",
                version.getOriginalName());
        // 文档名含企业别名时注入 COMPANY（年报常写「本集团」而不重复写品牌名）
        injectCompanyFromDocumentMeta(values, source, version);
        for (Map.Entry<String, List<String>> entry : activeCompanies().entrySet())
            if (entry.getValue().stream().anyMatch(alias -> containsIgnoreCase(text, alias)))
                add(values, entry.getKey(), "COMPANY", String.join(",", entry.getValue()));
        // 长车型名优先匹配：按长度降序，避免只命中短系列名
        List<String> models = new ArrayList<>();
        for (String model : activeModels()) models.add(model);
        models.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String model : models)
            if (containsIgnoreCase(text, model)) add(values, model, "MODEL", "");
        extractKeywordValues(values, text, "企业|公司|车企", "COMPANY");
        extractKeywordValues(values, text, "车型|车系", "MODEL");
        extractContextualModels(values, text);
        extractMatches(values, SALES, text, "SALES", 1);
        extractMatches(values, FINANCIAL, text, "FINANCIAL", 0);
        extractMatches(values, POLICY, text, "POLICY", 0);
        return values.values().stream()
            .filter(entity -> VISIBLE_ENTITY_TYPES.contains(entity.type()))
            .limit(MAX_ENTITIES_PER_CHUNK)
            .toList();
    }

    private void extractKeywordValues(Map<String, EntityValue> values, String text, String keyword, String type)
    {
        Pattern pattern = Pattern.compile("(?:" + keyword + ")\\s*[:：=]\\s*([^，,。；;\\n]{1,30})");
        Matcher matcher = pattern.matcher(text);
        while (matcher.find())
            for (String item : matcher.group(1).split("[/、和与]")) add(values, item.trim(), type, "");
    }

    /** 通用：只抽产品型号/已知系列，避免叙述词污染关联车型。 */
    private void extractContextualModels(Map<String, EntityValue> values, String text)
    {
        if (text == null || text.isBlank()) return;
        Matcher product = MODEL_PRODUCT.matcher(text);
        while (product.find()) addModelCandidate(values, product.group(1));
        Matcher series = Pattern.compile("(?:旗下|品牌)\\s*[:：为是]?\\s*([\\u4e00-\\u9fa5]{2,4})").matcher(text);
        while (series.find())
        {
            String name = series.group(1).trim();
            if (SERIES_ALLOWLIST.contains(name) && !isBlockedModel(name)) add(values, name, "MODEL", "");
        }
    }

    private void addModelCandidate(Map<String, EntityValue> values, String raw)
    {
        if (raw == null) return;
        String name = raw.replaceAll("^[的地得\\s「」『』\"']+|[\\s「」『』\"']+$", "").trim();
        if (name.length() < 2 || name.length() > 24) return;
        if (!name.matches(".*[A-Za-z0-9].*")) return; // 必须含字母或数字
        if (name.matches(".*(?:万辆|同比|比重|%|％).*")) return;
        if (isBlockedModel(name)) return;
        if (COMPANIES_REF().containsKey(name)) return;
        for (List<String> aliases : COMPANIES_REF().values())
            if (aliases.stream().anyMatch(a -> a.equalsIgnoreCase(name))) return;
        String canonical = vehicleCatalog != null ? vehicleCatalog.canonicalizeModel(name) : name;
        if (isBlockedModel(canonical)) return;
        add(values, canonical, "MODEL", "");
    }

    private Map<String, List<String>> activeCompanies()
    {
        Map<String, List<String>> merged = new LinkedHashMap<>(BASE_COMPANIES);
        if (vehicleCatalog != null)
            merged.putAll(vehicleCatalog.companyAliases());
        return merged;
    }

    private Iterable<String> activeModels()
    {
        LinkedHashSet<String> merged = new LinkedHashSet<>(BASE_MODELS);
        if (vehicleCatalog != null) merged.addAll(vehicleCatalog.modelTerms());
        return merged;
    }

    private Map<String, List<String>> COMPANIES_REF()
    {
        return activeCompanies();
    }

    /**
     * 问答收尾通用补边：按当前别名表重跑引用切片抽边（企业→车型/销量等归属边），
     * 不依赖 LLM，长安/奇瑞等只要同片共现即可挂上。
     */
    public int reindexCitedChunks(Collection<KnowledgeChunk> chunks)
    {
        if (chunks == null || chunks.isEmpty()) return 0;
        Set<Long> seen = new LinkedHashSet<>();
        int indexed = 0;
        for (KnowledgeChunk chunk : chunks)
        {
            if (chunk == null || chunk.getId() == null || !seen.add(chunk.getId())) continue;
            if (chunk.getContent() == null || chunk.getContent().isBlank()) continue;
            KnowledgeBase source = new KnowledgeBase();
            source.setId(chunk.getSourceId());
            source.setSourceName(chunk.getSourceName());
            source.setSourceType(chunk.getSourceType());
            KnowledgeVersion version = new KnowledgeVersion();
            version.setId(chunk.getVersionId());
            version.setVersionNo(chunk.getVersionNo());
            version.setOriginalName(chunk.getOriginalName());
            indexChunk(source, version, chunk);
            indexed++;
        }
        return indexed;
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
        String resolvedType = type;
        String resolvedName = clean;
        String resolvedAliases = aliases == null ? "" : aliases;
        if ("COMPANY".equals(type))
        {
            resolvedName = resolveCompanyName(clean);
            resolvedAliases = resolveCompanyAliases(resolvedName);
        }
        else if ("MODEL".equals(type))
        {
            if (isBlockedModel(clean)) return;
            resolvedName = resolveModelName(clean);
            if (isBlockedModel(resolvedName)) return;
        }
        values.putIfAbsent(resolvedType + ":" + normalize(resolvedName),
            new EntityValue(resolvedName, resolvedType, resolvedAliases));
    }

    private String resolveCompanyName(String raw)
    {
        if (raw == null || raw.isBlank()) return raw;
        String trimmed = raw.trim();
        if (vehicleCatalog != null)
        {
            String fromCatalog = vehicleCatalog.canonicalizeCompany(trimmed);
            // 目录未识别时回退 BASE，避免仅靠懂车帝子集漏掉特斯拉/天马等
            if (vehicleCatalog.isKnownCompanyAlias(trimmed)) return fromCatalog;
        }
        for (Map.Entry<String, List<String>> entry : BASE_COMPANIES.entrySet())
        {
            if (entry.getKey().equalsIgnoreCase(trimmed)) return entry.getKey();
            if (entry.getValue().stream().anyMatch(a -> a.equalsIgnoreCase(trimmed))) return entry.getKey();
        }
        return trimmed;
    }

    private String resolveCompanyAliases(String canonical)
    {
        if (vehicleCatalog != null && vehicleCatalog.isKnownCompanyAlias(canonical))
            return vehicleCatalog.companyAliasCsv(canonical);
        List<String> aliases = BASE_COMPANIES.get(canonical);
        if (aliases != null && !aliases.isEmpty()) return String.join(",", aliases);
        if (vehicleCatalog != null) return vehicleCatalog.companyAliasCsv(canonical);
        return canonical;
    }

    private String resolveModelName(String raw)
    {
        if (raw == null || raw.isBlank()) return raw;
        if (vehicleCatalog != null) return vehicleCatalog.canonicalizeModel(raw);
        return raw.trim();
    }

    private boolean isBlockedModel(String name)
    {
        if (name == null || name.isBlank()) return true;
        String trimmed = name.trim();
        if (MODEL_BLOCKLIST.contains(trimmed)) return true;
        if (MODEL_BLOCKLIST.stream().anyMatch(b -> b.equalsIgnoreCase(trimmed))) return true;
        // 纯英文小写 JSON/指标字段名（无数字、无中文）不当车型
        if (trimmed.matches("[a-z][a-z0-9_]{1,40}") && !trimmed.matches(".*\\d.*"))
        {
            if (trimmed.contains("_") || trimmed.endsWith("id") || trimmed.startsWith("yoy")
                || trimmed.contains("share") || trimmed.contains("market"))
                return true;
        }
        return false;
    }

    private Map<String, Object> assemble(List<Map<String, Object>> rows, Long centerId)
    {
        if (rows == null || rows.isEmpty()) return emptyGraph(centerId);
        Map<Long, Map<String, Object>> nodes = new LinkedHashMap<>();
        Map<String, Map<String, Object>> links = new LinkedHashMap<>();
        Map<String, Set<String>> linkSources = new LinkedHashMap<>();
        for (Map<String, Object> row : rows)
        {
            String fromType = string(row.get("fromType"));
            String toType = string(row.get("toType"));
            if (!VISIBLE_ENTITY_TYPES.contains(fromType) || !VISIBLE_ENTITY_TYPES.contains(toType)) continue;
            // 历史脏数据：JSON 键误抽成车型时，展示层直接丢弃
            if ("MODEL".equals(fromType) && isBlockedModel(string(row.get("fromName")))) continue;
            if ("MODEL".equals(toType) && isBlockedModel(string(row.get("toName")))) continue;
            Long fromId = longValue(row.get("fromId")); Long toId = longValue(row.get("toId"));
            if (fromId == null || toId == null || fromId.equals(toId)) continue;
            addViewNode(nodes, fromId, string(row.get("fromName")), fromType, centerId);
            addViewNode(nodes, toId, string(row.get("toName")), toType, centerId);
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
        Set<String> connected = new LinkedHashSet<>();
        for (Map<String, Object> link : links.values())
        {
            connected.add(String.valueOf(link.get("source")));
            connected.add(String.valueOf(link.get("target")));
        }
        List<Map<String, Object>> visibleNodes = nodes.values().stream()
            .filter(node -> connected.contains(String.valueOf(node.get("id"))))
            .toList();
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("nodes", visibleNodes);
        graph.put("links", new ArrayList<>(links.values()));
        graph.put("categories", categories(visibleNodes));
        graph.put("centerId", centerId);
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
        List<String> order = List.of("COMPANY", "MODEL", "SALES", "NEWS", "FINANCIAL", "POLICY");
        Set<String> present = new LinkedHashSet<>();
        for (Map<String, Object> node : nodes)
        {
            String type = string(node.get("entityType"));
            if (VISIBLE_ENTITY_TYPES.contains(type)) present.add(type);
        }
        List<Map<String, Object>> result = new ArrayList<>();
        for (String type : order)
            if (present.contains(type)) result.add(Map.of("name", typeLabel(type), "entityType", type));
        return result;
    }

    private Map<String, Object> emptyGraph(Long centerId)
    {
        Map<String, Object> graph = new LinkedHashMap<>();
        graph.put("nodes", List.of()); graph.put("links", List.of()); graph.put("categories", List.of()); graph.put("centerId", centerId);
        return graph;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> focusOnAskedCompanies(Map<String, Object> graph, Collection<String> focusTerms)
    {
        if (graph == null) return emptyGraph(null);
        Set<String> focus = expandFocusTerms(focusTerms);
        if (focus.isEmpty()) return graph;
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.getOrDefault("nodes", List.of());
        List<Map<String, Object>> links = (List<Map<String, Object>>) graph.getOrDefault("links", List.of());
        Set<String> focusCompanyIds = new LinkedHashSet<>();
        for (Map<String, Object> node : nodes)
        {
            if (!"COMPANY".equals(string(node.get("entityType")))) continue;
            if (matchesFocusName(string(node.get("name")), focus))
                focusCompanyIds.add(string(node.get("id")));
        }
        if (focusCompanyIds.isEmpty()) return graph;
        List<Map<String, Object>> keptLinks = new ArrayList<>();
        Set<String> keptNodeIds = new LinkedHashSet<>(focusCompanyIds);
        for (Map<String, Object> link : links)
        {
            String sourceId = string(link.get("source"));
            String targetId = string(link.get("target"));
            if (!focusCompanyIds.contains(sourceId) && !focusCompanyIds.contains(targetId)) continue;
            keptLinks.add(link);
            keptNodeIds.add(sourceId);
            keptNodeIds.add(targetId);
        }
        List<Map<String, Object>> keptNodes = nodes.stream()
            .filter(node -> keptNodeIds.contains(string(node.get("id"))))
            .filter(node -> !"COMPANY".equals(string(node.get("entityType")))
                || focusCompanyIds.contains(string(node.get("id"))))
            .toList();
        Set<String> finalIds = keptNodes.stream().map(node -> string(node.get("id"))).collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        keptLinks = keptLinks.stream()
            .filter(link -> finalIds.contains(string(link.get("source"))) && finalIds.contains(string(link.get("target"))))
            .toList();
        Map<String, Object> focused = new LinkedHashMap<>();
        focused.put("nodes", keptNodes);
        focused.put("links", keptLinks);
        focused.put("categories", categories(keptNodes));
        focused.put("centerId", graph.get("centerId"));
        focused.put("focusCompanies", new ArrayList<>(focusCompanyIds));
        return focused;
    }

    private Set<String> expandFocusTerms(Collection<String> focusTerms)
    {
        Set<String> focus = new LinkedHashSet<>();
        if (focusTerms == null) return focus;
        for (String term : focusTerms)
        {
            if (term == null || term.isBlank()) continue;
            focus.add(normalize(term));
            for (Map.Entry<String, List<String>> entry : activeCompanies().entrySet())
            {
                boolean hit = containsIgnoreCase(entry.getKey(), term)
                    || entry.getValue().stream().anyMatch(alias -> containsIgnoreCase(alias, term)
                        || containsIgnoreCase(term, alias));
                if (!hit) continue;
                focus.add(normalize(entry.getKey()));
                for (String alias : entry.getValue()) focus.add(normalize(alias));
            }
        }
        return focus;
    }

    private boolean matchesFocusName(String name, Set<String> focus)
    {
        if (name == null || name.isBlank() || focus.isEmpty()) return false;
        String normalizedName = normalize(name);
        if (focus.contains(normalizedName)) return true;
        for (String term : focus)
            if (!term.isBlank() && (normalizedName.contains(term) || term.contains(normalizedName)))
                return true;
        for (Map.Entry<String, List<String>> entry : activeCompanies().entrySet())
        {
            boolean nodeHit = containsIgnoreCase(entry.getKey(), name)
                || entry.getValue().stream().anyMatch(alias -> containsIgnoreCase(name, alias));
            if (!nodeHit) continue;
            if (focus.contains(normalize(entry.getKey()))) return true;
            if (entry.getValue().stream().anyMatch(alias -> focus.contains(normalize(alias)))) return true;
        }
        return false;
    }

    private List<String> companyNames(KnowledgeGraphNode company)
    {
        List<String> names = new ArrayList<>();
        if (company.getEntityName() != null && !company.getEntityName().isBlank())
            names.add(company.getEntityName());
        if (company.getAliases() != null)
            for (String alias : company.getAliases().split("[,，]"))
                if (alias != null && !alias.isBlank()) names.add(alias.trim());
        return names;
    }

    /** 多企业同一切片时，只把车企连到邻近出现的车型/销量，避免一家问销量却牵出全场车企。 */
    private boolean nearInText(String content, List<String> companyNames, String targetName)
    {
        if (content == null || content.isBlank() || targetName == null || targetName.isBlank()) return false;
        if (companyNames == null || companyNames.isEmpty()) return false;
        String lower = content.toLowerCase(Locale.ROOT);
        String target = targetName.toLowerCase(Locale.ROOT);
        int window = 220;
        boolean companySeen = false;
        for (String companyName : companyNames)
        {
            if (companyName == null || companyName.isBlank()) continue;
            String needle = companyName.toLowerCase(Locale.ROOT);
            int from = 0;
            while (true)
            {
                int index = lower.indexOf(needle, from);
                if (index < 0) break;
                companySeen = true;
                int start = Math.max(0, index - window);
                int end = Math.min(lower.length(), index + needle.length() + window);
                if (lower.substring(start, end).contains(target)) return true;
                from = index + Math.max(1, needle.length());
            }
        }
        return !companySeen;
    }

    private int categoryIndex(String type)
    {
        return switch (type)
        {
            case "COMPANY" -> 0; case "MODEL" -> 1; case "SALES" -> 2;
            case "NEWS" -> 3; case "FINANCIAL" -> 4; case "POLICY" -> 5;
            default -> 0;
        };
    }

    private int nodeSize(String type) { return "COMPANY".equals(type) ? 42 : 34; }
    private String typeLabel(String type) { return switch (type) { case "COMPANY" -> "企业"; case "MODEL" -> "车型";
        case "SALES" -> "销量"; case "NEWS" -> "新闻"; case "FINANCIAL" -> "财报"; case "POLICY" -> "政策";
        default -> "资料"; }; }
    private String relationFor(String type) { return switch (type) { case "MODEL" -> "关联车型"; case "SALES" -> "销量表现";
        case "NEWS" -> "相关新闻"; case "FINANCIAL" -> "披露财报"; case "POLICY" -> "关联政策";
        case "COMPANY" -> "关联企业"; default -> "关联"; }; }
    private String detectPeriod(String text) { Matcher matcher = PERIOD.matcher(text == null ? "" : text); if (!matcher.find()) return "";
        String quarter = matcher.group(2); return matcher.group(1) + (quarter == null ? "" : " " + normalizeQuarter(quarter)); }
    private String normalizeQuarter(String value) { if (value == null) return ""; String q=value.toUpperCase(Locale.ROOT).replace("第", "").replace("季度", "");
        return switch (q) { case "一", "1" -> "Q1"; case "二", "2" -> "Q2"; case "三", "3" -> "Q3"; case "四", "4" -> "Q4"; default -> q; }; }
    private String cleanPeriod(String value) { return value == null ? "" : value.trim(); }
    private String cleanType(String value) { return value == null ? "" : value.trim().toUpperCase(Locale.ROOT); }
    private List<Long> safeRoles(List<Long> roles) { return roles == null ? List.of() : roles; }
    private String normalize(String value) { return value.toLowerCase(Locale.ROOT).replaceAll("[\\s·._—–-]+", ""); }
    private void injectCompanyFromDocumentMeta(Map<String, EntityValue> values, KnowledgeBase source, KnowledgeVersion version)
    {
        String haystack = safe(source == null ? null : source.getSourceName()) + " "
            + safe(version == null ? null : version.getOriginalName());
        if (haystack.isBlank()) return;
        for (Map.Entry<String, List<String>> entry : activeCompanies().entrySet())
        {
            if (entry.getValue().stream().anyMatch(alias -> containsIgnoreCase(haystack, alias))
                || containsIgnoreCase(haystack, entry.getKey()))
                add(values, entry.getKey(), "COMPANY", String.join(",", entry.getValue()));
        }
    }

    private String safe(String value) { return value == null ? "" : value; }
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
        // 面板/供应链
        values.put("Tianma", List.of("Tianma", "天马")); values.put("AUO", List.of("AUO", "友达"));
        values.put("BOE", List.of("BOE", "京东方")); values.put("CSOT", List.of("CSOT", "China Star", "华星"));
        values.put("Faurecia", List.of("Faurecia", "佛吉亚"));
        values.put("Continental", List.of("Continental", "大陆集团", "大陆"));
        // 懂车帝目标车企：中英互通
        values.put("BYD", List.of("BYD", "比亚迪", "byd"));
        values.put("Chery", List.of("Chery", "奇瑞", "奇瑞汽车", "CHERY"));
        values.put("NIO", List.of("NIO", "蔚来", "Nio"));
        values.put("Li Auto", List.of("Li Auto", "LiAuto", "理想", "理想汽车"));
        values.put("Changan", List.of("Changan", "长安", "长安汽车", "CHANGAN"));
        values.put("Geely", List.of("Geely", "吉利", "吉利汽车"));
        values.put("Great Wall", List.of("Great Wall", "GWM", "长城", "长城汽车"));
        // 其他常见
        values.put("Tesla", List.of("Tesla", "特斯拉"));
        values.put("XPeng", List.of("XPeng", "小鹏"));
        values.put("SAIC", List.of("SAIC", "上汽")); values.put("GAC", List.of("GAC", "广汽"));
        values.put("BMW", List.of("BMW", "宝马")); values.put("Mercedes-Benz", List.of("Mercedes-Benz", "奔驰"));
        values.put("Volkswagen", List.of("Volkswagen", "大众"));
        return values;
    }

    private record EntityValue(String name, String type, String aliases) { }
    private record EvidenceWindow(String text, int start, int end) { }
}
