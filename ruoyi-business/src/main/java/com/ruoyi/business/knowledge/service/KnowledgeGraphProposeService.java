package com.ruoyi.business.knowledge.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.business.knowledge.domain.KnowledgeChunk;
import com.ruoyi.business.knowledge.domain.KnowledgeGraphNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 问答结束后由模型提议跨文档实体关系，经硬校验后自动写入知识图谱。
 * 模型提议永不绕过校验；校验失败不落库。
 */
@Service
public class KnowledgeGraphProposeService
{
    private static final Set<String> VISIBLE_TYPES = Set.of(
        "COMPANY", "MODEL", "SALES", "NEWS", "FINANCIAL", "POLICY");
    /** 归属边：企业拥有车型/销量口径，必须同一切片共现，禁止跨文件错挂。 */
    private static final Set<String> OWNERSHIP_RELATIONS = Set.of(
        "关联车型", "关联销量", "销量表现");
    /** 文档锚点边：新闻/财报/政策提及实体，通常同文件。 */
    private static final Set<String> DOCUMENT_MENTION_RELATIONS = Set.of(
        "来源提及");
    /** 桥接边：允许跨文件，类型必须与端点匹配。 */
    private static final Set<String> BRIDGE_RELATIONS = Set.of(
        "政策关联", "关联政策", "新闻关联", "相关新闻", "财报关联", "披露财报", "关联企业");
    private static final Set<String> RELATION_WHITELIST;
    static
    {
        LinkedHashSet<String> all = new LinkedHashSet<>();
        all.addAll(OWNERSHIP_RELATIONS);
        all.addAll(DOCUMENT_MENTION_RELATIONS);
        all.addAll(BRIDGE_RELATIONS);
        RELATION_WHITELIST = Set.copyOf(all);
    }
    private static final Set<String> DOCUMENT_TYPES = Set.of("NEWS", "FINANCIAL", "POLICY");
    private static final int MAX_COMMIT = 5;
    private static final Pattern CITATION_LABEL = Pattern.compile("(?i)^S(\\d+)$");
    private static final Pattern PERIOD = Pattern.compile("(?i)(20\\d{2})\\s*(?:年\\s*)?(Q[1-4]|第?[一二三四1234]季度)?");

    private final KnowledgeGraphService graphService;
    private final LlmRuntimeConfiguration llmConfiguration;
    private final HttpClient httpClient;

    @Value("${business.knowledge.llm.timeout-seconds:300}")
    private int requestTimeoutSeconds = 300;

    public KnowledgeGraphProposeService(KnowledgeGraphService graphService, LlmRuntimeConfiguration llmConfiguration)
    {
        this.graphService = graphService;
        this.llmConfiguration = llmConfiguration;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    /**
     * @return 实际写入条数；未触发或全部拒绝时为 0
     */
    public ProposeResult proposeAndCommit(String question, String answer, List<Map<String, Object>> citations,
        List<KnowledgeChunk> citedChunks, List<String> warnings, List<Map<String, Object>> retrievalLogs)
    {
        ProposeResult empty = new ProposeResult(0, List.of());
        if (citations == null || citations.isEmpty() || citedChunks == null || citedChunks.isEmpty()) return empty;

        Map<String, KnowledgeChunk> labelToChunk = mapCitations(citations, citedChunks);
        if (labelToChunk.isEmpty()) return empty;

        // 通用归属边：不靠 LLM。长安/奇瑞等只要企业别名命中且与车型同片共现，即可挂关联车型。
        int ownershipIndexed = 0;
        try
        {
            ownershipIndexed = graphService.reindexCitedChunks(labelToChunk.values());
            if (ownershipIndexed > 0 && retrievalLogs != null)
                retrievalLogs.add(logEntry("GRAPH_PROPOSE", "归属边重抽",
                    "reindexedChunks=" + ownershipIndexed, "SUCCESS"));
        }
        catch (Exception ex)
        {
            String msg = "GRAPH_PROPOSE ownership reindex failed: " + abbreviate(ex.getMessage(), 120);
            if (warnings != null) warnings.add(msg);
            logSkip(retrievalLogs, msg);
        }

        Set<Long> sourceIds = new LinkedHashSet<>();
        for (KnowledgeChunk chunk : labelToChunk.values())
            if (chunk.getSourceId() != null) sourceIds.add(chunk.getSourceId());
        if (sourceIds.size() < 2)
        {
            logSkip(retrievalLogs, "single-source citations, skip cross-doc propose");
            return new ProposeResult(ownershipIndexed, List.of());
        }

        if (llmConfiguration.getApiKey() == null || llmConfiguration.getApiKey().isBlank())
        {
            logSkip(retrievalLogs, "no llm api key, skip cross-doc propose");
            return new ProposeResult(ownershipIndexed, List.of());
        }

        List<ProposedRelation> proposed;
        try
        {
            proposed = callProposeModel(question, answer, citations, labelToChunk);
        }
        catch (Exception ex)
        {
            String msg = "GRAPH_PROPOSE llm failed: " + abbreviate(ex.getMessage(), 160);
            if (warnings != null) warnings.add(msg);
            logSkip(retrievalLogs, msg);
            return new ProposeResult(ownershipIndexed, List.of(msg));
        }
        if (proposed.isEmpty())
        {
            logSkip(retrievalLogs, "model returned no relations");
            return new ProposeResult(ownershipIndexed, List.of());
        }

        List<String> skipReasons = new ArrayList<>();
        int committed = 0;
        for (ProposedRelation rel : proposed)
        {
            if (committed >= MAX_COMMIT) break;
            // 归属边已由 reindex 处理；LLM 只接受跨源桥接，避免再错挂车型
            String relationType = rel.relationType() == null ? "" : rel.relationType().trim();
            if (OWNERSHIP_RELATIONS.contains(relationType))
            {
                skipReasons.add("ownership left to deterministic reindex: " + relationType);
                continue;
            }
            String reject = validateAndCommit(rel, labelToChunk);
            if (reject != null)
            {
                skipReasons.add(reject);
                continue;
            }
            committed++;
        }
        if (!skipReasons.isEmpty())
        {
            String summary = "GRAPH_PROPOSE skipped=" + skipReasons.size() + ": "
                + String.join("; ", skipReasons.stream().limit(5).toList());
            if (warnings != null) warnings.add(summary);
            logSkip(retrievalLogs, summary);
        }
        int total = committed + ownershipIndexed;
        if (committed > 0 && retrievalLogs != null)
            retrievalLogs.add(logEntry("GRAPH_PROPOSE", "图谱提议写入",
                "committed=" + committed + " of " + proposed.size(), "SUCCESS"));
        return new ProposeResult(total, skipReasons);
    }

    /** 校验单条提议；供自测/无 LLM 路径复用。通过则写入并返回 null，否则返回拒绝原因。 */
    public String validateAndCommit(ProposedRelation rel, Map<String, KnowledgeChunk> labelToChunk)
    {
        if (rel == null) return "null relation";
        String fromType = safeType(rel.fromType());
        String toType = safeType(rel.toType());
        String relationType = rel.relationType() == null ? "" : rel.relationType().trim();
        if (!VISIBLE_TYPES.contains(fromType)) return "bad fromType=" + rel.fromType();
        if (!VISIBLE_TYPES.contains(toType)) return "bad toType=" + rel.toType();
        if (!RELATION_WHITELIST.contains(relationType)) return "bad relationType=" + rel.relationType();
        if (rel.fromName() == null || rel.fromName().isBlank() || rel.toName() == null || rel.toName().isBlank())
            return "empty entity name";

        String schemaReject = validateTypePair(fromType, toType, relationType);
        if (schemaReject != null) return schemaReject;

        String fromLabel = normalizeLabel(rel.fromCitation());
        String toLabel = normalizeLabel(rel.toCitation());
        KnowledgeChunk fromChunk = labelToChunk.get(fromLabel);
        KnowledgeChunk toChunk = labelToChunk.get(toLabel);
        if (fromChunk == null) return "unresolvable fromCitation=" + rel.fromCitation();
        if (toChunk == null) return "unresolvable toCitation=" + rel.toCitation();

        String fromName = rel.fromName().trim();
        String toName = rel.toName().trim();

        if (OWNERSHIP_RELATIONS.contains(relationType))
            return commitOwnership(fromName, fromType, toName, toType, relationType, rel.evidence(),
                labelToChunk, fromChunk, toChunk);
        if (DOCUMENT_MENTION_RELATIONS.contains(relationType))
            return commitDocumentMention(fromName, fromType, toName, toType, relationType, rel.evidence(),
                fromChunk, toChunk);
        if (BRIDGE_RELATIONS.contains(relationType))
            return commitBridge(fromName, fromType, toName, toType, relationType, rel.evidence(),
                labelToChunk, fromChunk, toChunk);
        return "unsupported relationType=" + relationType;
    }

    /**
     * 通用端点约束：
     * 企业→车型/销量；新闻/财报/政策→实体；政策/新闻/财报↔企业；企业↔企业。
     */
    String validateTypePair(String fromType, String toType, String relationType)
    {
        return switch (relationType)
        {
            case "关联车型" ->
                "COMPANY".equals(fromType) && "MODEL".equals(toType) ? null
                    : "关联车型 requires COMPANY→MODEL";
            case "关联销量", "销量表现" ->
                "COMPANY".equals(fromType) && "SALES".equals(toType) ? null
                    : "销量边 requires COMPANY→SALES";
            case "来源提及" ->
                DOCUMENT_TYPES.contains(fromType)
                    && Set.of("COMPANY", "MODEL", "SALES").contains(toType) ? null
                    : "来源提及 requires NEWS|FINANCIAL|POLICY → COMPANY|MODEL|SALES";
            case "政策关联", "关联政策" ->
                ("POLICY".equals(fromType) && "COMPANY".equals(toType))
                    || ("COMPANY".equals(fromType) && "POLICY".equals(toType)) ? null
                    : "政策关联 requires POLICY↔COMPANY";
            case "新闻关联", "相关新闻" ->
                ("NEWS".equals(fromType) && "COMPANY".equals(toType))
                    || ("COMPANY".equals(fromType) && "NEWS".equals(toType)) ? null
                    : "新闻关联 requires NEWS↔COMPANY";
            case "财报关联", "披露财报" ->
                ("FINANCIAL".equals(fromType) && "COMPANY".equals(toType))
                    || ("COMPANY".equals(fromType) && "FINANCIAL".equals(toType)) ? null
                    : "财报关联 requires FINANCIAL↔COMPANY";
            case "关联企业" ->
                "COMPANY".equals(fromType) && "COMPANY".equals(toType) ? null
                    : "关联企业 requires COMPANY→COMPANY";
            default -> "unknown relation schema";
        };
    }

    private String commitOwnership(String fromName, String fromType, String toName, String toType,
        String relationType, String evidence, Map<String, KnowledgeChunk> labelToChunk,
        KnowledgeChunk fromChunk, KnowledgeChunk toChunk)
    {
        KnowledgeChunk joint = null;
        if (nameAppearsIn(fromChunk, fromName) && nameAppearsIn(fromChunk, toName)) joint = fromChunk;
        else if (nameAppearsIn(toChunk, fromName) && nameAppearsIn(toChunk, toName)) joint = toChunk;
        if (joint == null) joint = findChunkWithBothNames(labelToChunk, fromName, toName);
        if (joint == null)
            return "ownership requires company+target co-occur in same citation chunk";

        EvidenceHit evidenceHit = locateEvidence(joint, evidence);
        if (evidenceHit == null) evidenceHit = locateEvidence(joint, toName);
        if (evidenceHit == null) evidenceHit = locateEvidence(joint, fromName);
        if (evidenceHit == null) return "evidence not found in ownership chunk";

        return upsertAndInsert(fromName, fromType, toName, toType, relationType, joint, evidenceHit);
    }

    private String commitDocumentMention(String fromName, String fromType, String toName, String toType,
        String relationType, String evidence, KnowledgeChunk fromChunk, KnowledgeChunk toChunk)
    {
        KnowledgeChunk docChunk = nameAppearsIn(fromChunk, fromName) ? fromChunk
            : (nameAppearsIn(toChunk, fromName) ? toChunk : fromChunk);
        if (!nameAppearsIn(docChunk, fromName)) return "document name not found in citation";
        if (!nameAppearsIn(docChunk, toName) && !nameAppearsIn(toChunk, toName) && !nameAppearsIn(fromChunk, toName))
            return "mentioned entity not found in citation";

        KnowledgeChunk evidenceChunk = nameAppearsIn(docChunk, toName) ? docChunk
            : (nameAppearsIn(fromChunk, toName) ? fromChunk : toChunk);
        if (docChunk.getSourceId() != null && evidenceChunk.getSourceId() != null
            && !docChunk.getSourceId().equals(evidenceChunk.getSourceId()))
            return "来源提及 must stay in same source file";

        EvidenceHit evidenceHit = locateEvidence(evidenceChunk, evidence);
        if (evidenceHit == null) evidenceHit = locateEvidence(evidenceChunk, toName);
        if (evidenceHit == null) return "evidence not found for 来源提及";
        return upsertAndInsert(fromName, fromType, toName, toType, relationType, evidenceChunk, evidenceHit);
    }

    private String commitBridge(String fromName, String fromType, String toName, String toType,
        String relationType, String evidence, Map<String, KnowledgeChunk> labelToChunk,
        KnowledgeChunk fromChunk, KnowledgeChunk toChunk)
    {
        Long fromSource = fromChunk.getSourceId();
        Long toSource = toChunk.getSourceId();
        boolean crossSource = fromSource != null && toSource != null && !fromSource.equals(toSource);

        if (!nameAppearsIn(fromChunk, fromName) || !nameAppearsIn(toChunk, toName))
        {
            KnowledgeChunk remappedFrom = findChunkForName(labelToChunk, fromName, null);
            KnowledgeChunk remappedTo = findChunkForName(labelToChunk, toName,
                remappedFrom == null ? null : remappedFrom.getSourceId());
            if (remappedFrom != null && remappedTo != null)
            {
                fromChunk = remappedFrom;
                toChunk = remappedTo;
                fromSource = fromChunk.getSourceId();
                toSource = toChunk.getSourceId();
                crossSource = fromSource != null && toSource != null && !fromSource.equals(toSource);
            }
        }

        if (!crossSource)
            return "bridge relation requires different sourceId";
        if (!nameAppearsIn(fromChunk, fromName)) return "fromName not found in from citation";
        if (!nameAppearsIn(toChunk, toName)) return "toName not found in to citation";

        EvidenceHit fromHit = locateEvidence(fromChunk, evidence);
        EvidenceHit toHit = locateEvidence(toChunk, evidence);
        if (fromHit == null) fromHit = locateEvidence(fromChunk, fromName);
        if (toHit == null) toHit = locateEvidence(toChunk, toName);
        EvidenceHit evidenceHit = fromHit != null ? fromHit : toHit;
        if (evidenceHit == null) return "evidence not found in citation chunks";
        KnowledgeChunk evidenceChunk = fromHit != null ? fromChunk : toChunk;
        return upsertAndInsert(fromName, fromType, toName, toType, relationType, evidenceChunk, evidenceHit);
    }

    private String upsertAndInsert(String fromName, String fromType, String toName, String toType,
        String relationType, KnowledgeChunk evidenceChunk, EvidenceHit evidenceHit)
    {
        KnowledgeGraphNode fromNode = graphService.upsertVisibleNode(fromName, fromType, "");
        KnowledgeGraphNode toNode = graphService.upsertVisibleNode(toName, toType, "");
        if (fromNode == null || toNode == null || fromNode.getId() == null || toNode.getId() == null)
            return "upsert node failed";
        String period = detectPeriod(chunkContent(evidenceChunk));
        graphService.insertProposedRelation(fromNode, toNode, relationType, evidenceChunk,
            evidenceHit.snippet(), evidenceHit.start(), evidenceHit.end(), period);
        return null;
    }

    private KnowledgeChunk findChunkWithBothNames(Map<String, KnowledgeChunk> labelToChunk,
        String fromName, String toName)
    {
        for (KnowledgeChunk chunk : labelToChunk.values())
            if (chunk != null && nameAppearsIn(chunk, fromName) && nameAppearsIn(chunk, toName))
                return chunk;
        return null;
    }

    private KnowledgeChunk findChunkForName(Map<String, KnowledgeChunk> labelToChunk, String name,
        Long excludeSourceId)
    {
        if (name == null || name.isBlank()) return null;
        for (KnowledgeChunk chunk : labelToChunk.values())
        {
            if (chunk == null) continue;
            if (excludeSourceId != null && excludeSourceId.equals(chunk.getSourceId())) continue;
            if (nameAppearsIn(chunk, name)) return chunk;
        }
        return null;
    }

    private List<ProposedRelation> callProposeModel(String question, String answer,
        List<Map<String, Object>> citations, Map<String, KnowledgeChunk> labelToChunk) throws Exception
    {
        String system = """
            你是知识图谱关系抽取助手。只输出 JSON，不要 Markdown。
            格式：{"relations":[{"fromName":"...","fromType":"...","toName":"...","toType":"...",
            "relationType":"...","evidence":"原文短句","fromCitation":"S1","toCitation":"S2"}]}
            实体类型仅限：COMPANY/MODEL/SALES/NEWS/FINANCIAL/POLICY
            【关系模式，必须严格遵守】
            1) 关联车型：COMPANY→MODEL；两端必须出现在同一引用片段；禁止把 A 公司车型挂到 B 公司
            2) 关联销量/销量表现：COMPANY→SALES；两端同片段共现
            3) 来源提及：NEWS|FINANCIAL|POLICY → COMPANY|MODEL|SALES；同文件
            4) 政策关联/关联政策：POLICY↔COMPANY；两端 sourceId 不同
            5) 新闻关联/相关新闻：NEWS↔COMPANY；两端 sourceId 不同
            6) 财报关联/披露财报：FINANCIAL↔COMPANY；两端 sourceId 不同
            7) 关联企业：COMPANY→COMPANY；两端 sourceId 不同
            evidence 必须是原文短句；不确定则输出空数组；最多 5 条
            """;
        StringBuilder user = new StringBuilder();
        user.append("问题：").append(nullToEmpty(question)).append('\n');
        user.append("回答摘要：").append(abbreviate(nullToEmpty(answer), 800)).append("\n\n引用：\n");
        List<String> labelOrder = new ArrayList<>();
        Map<String, Long> labelSource = new LinkedHashMap<>();
        for (Map.Entry<String, KnowledgeChunk> entry : labelToChunk.entrySet())
        {
            String label = entry.getKey();
            KnowledgeChunk chunk = entry.getValue();
            labelOrder.add(label);
            if (chunk.getSourceId() != null) labelSource.put(label, chunk.getSourceId());
            String file = firstNonBlank(chunk.getOriginalName(), chunk.getSourceName());
            String snippet = firstNonBlank(chunk.getContent(), chunk.getSourceSnippet());
            user.append(label).append(" | 文件=").append(file)
                .append(" | 类型=").append(nullToEmpty(chunk.getSourceType()))
                .append(" | sourceId=").append(string(chunk.getSourceId()))
                .append("\n片段：").append(abbreviate(snippet, 500))
                .append("\n\n");
        }
        user.append("跨文件候选对（仅用于政策/新闻/财报/关联企业桥接边）：\n");
        int pairs = 0;
        for (int i = 0; i < labelOrder.size() && pairs < 12; i++)
        {
            for (int j = i + 1; j < labelOrder.size() && pairs < 12; j++)
            {
                String a = labelOrder.get(i);
                String b = labelOrder.get(j);
                Long sa = labelSource.get(a);
                Long sb = labelSource.get(b);
                if (sa == null || sb == null || sa.equals(sb)) continue;
                user.append(a).append("(sourceId=").append(sa).append(") <-> ")
                    .append(b).append("(sourceId=").append(sb).append(")\n");
                pairs++;
            }
        }
        if (pairs == 0) user.append("（无）\n");
        JSONArray messages = new JSONArray();
        messages.add(message("system", system));
        messages.add(message("user", user.toString()));
        JSONObject payload = new JSONObject();
        payload.put("model", llmConfiguration.getModel());
        payload.put("messages", messages);
        payload.put("temperature", 0);
        payload.put("max_tokens", 800);
        payload.put("stream", false);
        String content = sendLlm(payload);
        return parseRelations(content);
    }

    List<ProposedRelation> parseRelations(String content)
    {
        List<ProposedRelation> list = new ArrayList<>();
        if (content == null || content.isBlank()) return list;
        String json = extractJsonObject(content);
        try
        {
            JSONObject root = JSONObject.parseObject(json);
            JSONArray arr = root.getJSONArray("relations");
            if (arr == null) return list;
            for (int i = 0; i < arr.size() && list.size() < MAX_COMMIT; i++)
            {
                JSONObject item = arr.getJSONObject(i);
                if (item == null) continue;
                list.add(new ProposedRelation(
                    item.getString("fromName"),
                    item.getString("fromType"),
                    item.getString("toName"),
                    item.getString("toType"),
                    item.getString("relationType"),
                    item.getString("evidence"),
                    item.getString("fromCitation"),
                    item.getString("toCitation")));
            }
        }
        catch (Exception ignored)
        {
            return List.of();
        }
        return list;
    }

    private Map<String, KnowledgeChunk> mapCitations(List<Map<String, Object>> citations,
        List<KnowledgeChunk> citedChunks)
    {
        Map<Long, KnowledgeChunk> byId = new LinkedHashMap<>();
        for (KnowledgeChunk chunk : citedChunks)
            if (chunk != null && chunk.getId() != null) byId.put(chunk.getId(), chunk);

        Map<String, KnowledgeChunk> labelToChunk = new LinkedHashMap<>();
        for (Map<String, Object> citation : citations)
        {
            String label = normalizeLabel(string(citation.get("citationLabel")));
            if (label.isEmpty()) continue;
            Long id = longValue(citation.get("id"));
            KnowledgeChunk chunk = id == null ? null : byId.get(id);
            if (chunk == null)
            {
                chunk = new KnowledgeChunk();
                chunk.setId(id);
                chunk.setSourceId(longValue(citation.get("sourceId")));
                chunk.setVersionId(longValue(citation.get("versionId")));
                chunk.setSourceName(string(citation.get("sourceName")));
                chunk.setOriginalName(string(citation.get("originalName")));
                chunk.setSourceType(string(citation.get("sourceType")));
                chunk.setSourceSnippet(string(citation.get("sourceSnippet")));
                chunk.setContent(string(citation.get("sourceSnippet")));
                chunk.setPageStart(intValue(citation.get("pageStart")));
                chunk.setPageEnd(intValue(citation.get("pageEnd")));
            }
            else if ((chunk.getContent() == null || chunk.getContent().isBlank())
                && citation.get("sourceSnippet") != null)
            {
                chunk.setContent(string(citation.get("sourceSnippet")));
            }
            labelToChunk.put(label, chunk);
        }
        return labelToChunk;
    }

    private EvidenceHit locateEvidence(KnowledgeChunk chunk, String evidence)
    {
        String content = chunkContent(chunk);
        if (content.isEmpty()) return null;
        if (evidence == null || evidence.isBlank()) return null;

        int index = indexOfNormalized(content, evidence.trim());
        if (index < 0) return null;

        int matchLen = Math.min(evidence.trim().length(), content.length() - index);
        if (matchLen < 1) matchLen = Math.min(8, content.length() - index);
        int start = Math.max(0, index - 40);
        int end = Math.min(content.length(), index + matchLen + 80);
        return new EvidenceHit(content.substring(start, end), start, end);
    }

    private boolean nameAppearsIn(KnowledgeChunk chunk, String name)
    {
        if (name == null || name.isBlank()) return false;
        return indexOfNormalized(chunkContent(chunk), name.trim()) >= 0;
    }

    private String chunkContent(KnowledgeChunk chunk)
    {
        if (chunk == null) return "";
        String content = chunk.getContent();
        if (content == null || content.isBlank()) content = chunk.getSourceSnippet();
        return content == null ? "" : content;
    }

    private int indexOfNormalized(String content, String needle)
    {
        int direct = content.indexOf(needle);
        if (direct >= 0) return direct;
        String cLower = content.toLowerCase(Locale.ROOT);
        String nLower = needle.toLowerCase(Locale.ROOT);
        int idx = cLower.indexOf(nLower);
        if (idx >= 0) return idx;
        String cNorm = normalizeLoose(content);
        String nNorm = normalizeLoose(needle);
        if (nNorm.isEmpty()) return -1;
        int nIdx = cNorm.indexOf(nNorm);
        if (nIdx < 0) return -1;
        // approximate back to original index by scanning
        int approx = Math.min(content.length() - 1, nIdx);
        return Math.max(0, approx);
    }

    private String normalizeLoose(String value)
    {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\s\\u3000·._—–\\-，,。；;：:\"'「」『』【】\\[\\]()（）]+", "");
    }

    private String sendLlm(JSONObject payload) throws Exception
    {
        HttpRequest request = HttpRequest.newBuilder(URI.create(llmConfiguration.getApiUrl()))
            .timeout(Duration.ofSeconds(Math.min(120, Math.max(30, requestTimeoutSeconds))))
            .header("Authorization", "Bearer " + llmConfiguration.getApiKey())
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toJSONString(), StandardCharsets.UTF_8))
            .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IllegalStateException("LLM HTTP " + response.statusCode() + ": " + abbreviate(response.body(), 200));
        JSONObject root = JSONObject.parseObject(response.body());
        JSONArray choices = root.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) throw new IllegalStateException("LLM empty choices");
        String content = choices.getJSONObject(0).getJSONObject("message").getString("content");
        if (content == null || content.isBlank()) throw new IllegalStateException("LLM empty content");
        return content.trim();
    }

    private static JSONObject message(String role, String content)
    {
        JSONObject msg = new JSONObject();
        msg.put("role", role);
        msg.put("content", content);
        return msg;
    }

    private static String extractJsonObject(String content)
    {
        String trimmed = content.trim();
        if (trimmed.startsWith("```"))
        {
            int start = trimmed.indexOf('{');
            int end = trimmed.lastIndexOf('}');
            if (start >= 0 && end > start) return trimmed.substring(start, end + 1);
        }
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) return trimmed.substring(start, end + 1);
        return trimmed;
    }

    private static String normalizeLabel(String label)
    {
        if (label == null) return "";
        String t = label.trim().toUpperCase(Locale.ROOT);
        Matcher m = CITATION_LABEL.matcher(t);
        if (m.matches()) return "S" + m.group(1);
        if (t.startsWith("[") && t.endsWith("]")) t = t.substring(1, t.length() - 1).trim().toUpperCase(Locale.ROOT);
        Matcher m2 = CITATION_LABEL.matcher(t);
        return m2.matches() ? "S" + m2.group(1) : t;
    }

    private static String safeType(String type)
    {
        return type == null ? "" : type.trim().toUpperCase(Locale.ROOT);
    }

    private static String detectPeriod(String text)
    {
        Matcher matcher = PERIOD.matcher(text == null ? "" : text);
        if (!matcher.find()) return "";
        String quarter = matcher.group(2);
        return matcher.group(1) + (quarter == null ? "" : " " + quarter);
    }

    private void logSkip(List<Map<String, Object>> logs, String detail)
    {
        if (logs != null) logs.add(logEntry("GRAPH_PROPOSE", "图谱提议", detail, "SKIPPED"));
    }

    private static Map<String, Object> logEntry(String stage, String title, String detail, String status)
    {
        Map<String, Object> log = new LinkedHashMap<>();
        log.put("stage", stage);
        log.put("title", title);
        log.put("detail", detail);
        log.put("status", status);
        log.put("durationMs", 0);
        return log;
    }

    private static String nullToEmpty(String v) { return v == null ? "" : v; }
    private static String string(Object v) { return v == null ? "" : String.valueOf(v); }
    private static String firstNonBlank(String a, String b)
    {
        if (a != null && !a.isBlank()) return a;
        return b == null ? "" : b;
    }
    private static String abbreviate(String value, int max)
    {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
    private static Long longValue(Object value)
    {
        if (value == null) return null;
        if (value instanceof Number n) return n.longValue();
        try { return Long.valueOf(String.valueOf(value)); } catch (Exception e) { return null; }
    }
    private static Integer intValue(Object value)
    {
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        try { return Integer.valueOf(String.valueOf(value)); } catch (Exception e) { return null; }
    }

    public record ProposedRelation(String fromName, String fromType, String toName, String toType,
        String relationType, String evidence, String fromCitation, String toCitation) {}

    public record ProposeResult(int committed, List<String> skipReasons) {}

    private record EvidenceHit(String snippet, int start, int end) {}
}
