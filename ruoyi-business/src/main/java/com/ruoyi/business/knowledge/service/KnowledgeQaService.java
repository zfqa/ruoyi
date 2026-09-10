package com.ruoyi.business.knowledge.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.business.knowledge.domain.KnowledgeChunk;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 基于当前有效知识切片生成带可校验来源编号的回答。 */
@Service
public class KnowledgeQaService
{
    private static final Pattern TIME_PATTERN = Pattern.compile("(?i)(20\\d{2}(?:\\s*Q[1-4])?|Y\\d{2}(?:Q[1-4]|Q1[-_]Q3)?)");
    private final KnowledgeIngestService ingestService;
    private final KnowledgeCitationValidator citationValidator = new KnowledgeCitationValidator();
    private final HttpClient httpClient;
    private final LlmRuntimeConfiguration llmConfiguration;
    private KnowledgeGraphService graphService;

    @Value("${business.knowledge.llm.timeout-seconds:300}")
    private int requestTimeoutSeconds = 300;

    public KnowledgeQaService(KnowledgeIngestService ingestService, LlmRuntimeConfiguration llmConfiguration)
    {
        this.ingestService = ingestService;
        this.llmConfiguration = llmConfiguration;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Autowired
    public void setGraphService(KnowledgeGraphService graphService)
    {
        this.graphService = graphService;
    }

    public Map<String, Object> llmConfiguration()
    {
        return llmConfiguration.view();
    }

    public synchronized Map<String, Object> updateLlmConfiguration(String newApiUrl, String newModel,
        String newApiKey, boolean clearApiKey)
    {
        return llmConfiguration.update(newApiUrl, newModel, newApiKey, clearApiKey);
    }

    public Map<String, Object> testLlmConfiguration() throws Exception
    {
        if (llmConfiguration.getApiKey().isBlank()) throw new IllegalArgumentException("API Key未配置");
        long started = System.nanoTime();
        JSONArray messages = new JSONArray();
        messages.add(message("user", "只回复OK"));
        JSONObject payload = new JSONObject();
        payload.put("model", llmConfiguration.getModel()); payload.put("messages", messages); payload.put("temperature", 0); payload.put("stream", false);
        sendLlmRequest(payload);
        return Map.of("success", true, "model", llmConfiguration.getModel(), "durationMs", elapsedMillis(started));
    }

    public Map<String, Object> ask(String question, String sourceType, List<Long> roleIds, boolean admin) throws Exception
    {
        return ask(question, sourceType, roleIds, admin, true);
    }

    public Map<String, Object> ask(String question, String sourceType, List<Long> roleIds, boolean admin,
        boolean includeNews) throws Exception
    {
        return ask(question, sourceType, roleIds, admin, includeNews, (progress, stage, logs) -> { });
    }

    public Map<String, Object> ask(String question, String sourceType, List<Long> roleIds, boolean admin,
        boolean includeNews, QaProgressListener progressListener) throws Exception
    {
        if (question == null || question.trim().length() < 2) throw new IllegalArgumentException("问题至少2个字符");
        String actualQuestion = question.trim();
        List<Map<String, Object>> logs = new ArrayList<>();
        notifyProgress(progressListener, 5, "解析问题与生成检索计划", logs);
        String policyQuery = policyContextQuery(actualQuestion);
        logs.add(log("QUERY_BUILD", "生成检索词", questionProfile(actualQuestion),
            "数据检索词：" + actualQuestion + "；政策检索词：" + policyQuery, 0, "SUCCESS"));
        notifyProgress(progressListener, 10, "已理解问题并生成多路检索词", logs);
        long retrievalStarted = System.nanoTime();
        RetrievalResult retrieval = retrieveEvidence(actualQuestion, sourceType, roleIds, admin, includeNews,
            logs, progressListener);
        List<KnowledgeChunk> chunks = retrieval.chunks();
        Map<String, Integer> sourceBreakdown = sourceBreakdown(chunks);
        logs.add(log("SEARCH", "混合证据检索", actualQuestion,
            chunks.size() + " 个候选切片（结构化指标 " + retrieval.metricHits() + " 个）",
            elapsedMillis(retrievalStarted), "SUCCESS"));
        notifyProgress(progressListener, 35, "已完成多源检索", logs);
        if (chunks.isEmpty()) throw new IllegalArgumentException("当前有效知识版本中没有找到相关资料");

        String answer;
        String answerMode = "LLM_VERIFIED";
        List<String> warnings = new ArrayList<>();
        KnowledgeCitationValidator.ValidationResult validation;
        notifyProgress(progressListener, 50, "正在生成可溯源回答", logs);
        if (llmConfiguration.getApiKey().isBlank())
        {
            answer = buildExtractiveFallback(actualQuestion, chunks);
            validation = citationValidator.validate(answer, chunks);
            answerMode = "EXTRACTIVE_FALLBACK";
            warnings.add("未配置ARK_API_KEY，已返回经过引用校验的原文摘录");
            logs.add(log("GENERATE", "LLM生成", llmConfiguration.getModel(), "未配置密钥，使用原文降级", 0, "FALLBACK"));
            notifyProgress(progressListener, 80, "已生成原文降级回答", logs);
        }
        else
        {
            long llmStarted = System.nanoTime();
            boolean llmResponded = false;
            try
            {
                answer = callLlm(actualQuestion, chunks, null);
                llmResponded = true;
                logs.add(log("GENERATE", "LLM生成", llmConfiguration.getModel(), "回答生成完成",
                    elapsedMillis(llmStarted), "SUCCESS"));
                notifyProgress(progressListener, 75, "LLM回答已生成，正在校验引用", logs);
                long validationStarted = System.nanoTime();
                try
                {
                    validation = citationValidator.validate(answer, chunks);
                    logs.add(log("VERIFY", "逐句引用校验", "来源编号+数字+语义窗口",
                        validation.getClaims().size() + " 个事实句通过",
                        elapsedMillis(validationStarted), "SUCCESS"));
                    notifyProgress(progressListener, 90, "逐句引用校验通过", logs);
                }
                catch (Exception validationError)
                {
                    logs.add(log("VERIFY", "首次引用校验", "来源编号+数字+语义窗口",
                        abbreviate(validationError.getMessage(), 160), elapsedMillis(validationStarted), "RETRY"));
                    notifyProgress(progressListener, 80, "首次引用未通过，正在修复", logs);
                    long repairStarted = System.nanoTime();
                    answer = callLlm(actualQuestion, chunks,
                        "上一次回答未通过引用校验：" + validationError.getMessage()
                        + "\n请重写整个回答。每个事实句单独附来源，数字、主体、时间必须与对应原文一致。"
                    );
                    validation = citationValidator.validate(answer, chunks);
                    answerMode = "LLM_REPAIRED";
                    logs.add(log("REPAIR", "引用修复", "仅使用原候选来源",
                        validation.getClaims().size() + " 个事实句通过",
                        elapsedMillis(repairStarted), "SUCCESS"));
                    notifyProgress(progressListener, 90, "引用修复与校验通过", logs);
                }
            }
            catch (Exception llmError)
            {
                answer = buildExtractiveFallback(actualQuestion, chunks);
                validation = citationValidator.validate(answer, chunks);
                answerMode = "EXTRACTIVE_FALLBACK";
                String reason = llmResponded
                    ? "LLM连接正常，但生成内容未通过严格引用校验"
                    : "LLM请求失败";
                warnings.add(reason + "，已返回经过引用校验的确定性结果："
                    + abbreviate(llmError.getMessage(), 180));
                logs.add(log("GENERATE", "安全降级", llmConfiguration.getModel(), reason + "；返回可溯源确定性结果",
                    elapsedMillis(llmStarted), "FALLBACK"));
                notifyProgress(progressListener, 85, llmResponded
                    ? "生成内容未通过引用校验，已切换确定性回答"
                    : "LLM请求失败，已生成可溯源确定性回答", logs);
            }
        }
        List<Map<String, Object>> citations = new ArrayList<>();
        for (Integer index : validation.getCitedIndexes())
            citations.add(toCitation("S" + index, chunks.get(index - 1), validation.getEvidenceForSource(index)));
        List<KnowledgeChunk> citedChunks = validation.getCitedIndexes().stream().map(index -> chunks.get(index - 1)).toList();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", answer);
        result.put("claims", validation.getClaims());
        result.put("citations", citations);
        result.put("model", llmConfiguration.getModel());
        result.put("answerMode", answerMode);
        result.put("citationCoveragePercent", 100);
        result.put("citationPolicy", "STRICT_VERIFIED_ONLY");
        result.put("warnings", warnings);
        if (includeNews && sourceBreakdown.get("NEWS") == 0 && sourceBreakdown.get("POLICY") == 0)
            warnings.add("当前问题未检索到匹配新闻或政策，本次回答只使用分析结果和固定资料");
        result.put("newsExplanationEnabled", includeNews);
        result.put("sourceBreakdown", sourceBreakdown);
        result.put("queryPlan", queryPlan(actualQuestion, sourceType, retrieval.metricHits(),
            validation.getClaims().size(), includeNews, sourceBreakdown));
        result.put("retrievalLogs", logs);
        result.put("analysisTrace", analysisTrace(actualQuestion, chunks, validation, answerMode));
        result.put("graph", graphService == null ? emptyGraph() : graphService.graphForChunks(citedChunks, roleIds, admin));
        notifyProgress(progressListener, 100, "问答与来源图谱生成完成", logs);
        return result;
    }

    public List<Map<String, Object>> previewQueryPlan(String question, String sourceType, boolean includeNews)
    {
        String actualQuestion = question == null ? "" : question.trim();
        List<Map<String, Object>> plan = new ArrayList<>();
        plan.add(step(1, "理解你的问题", intentNarrative(actualQuestion), questionProfile(actualQuestion), "DONE"));
        plan.add(step(2, "生成检索词", "为数据结论和外部解释分别生成检索词",
            "数据：" + actualQuestion + "；政策：" + policyContextQuery(actualQuestion), "PENDING"));
        plan.add(step(3, "查询结构化数据", "先从已生成报告中确认指标、时间和数值",
            sourceType == null || sourceType.isBlank() ? "REPORT指标库" : sourceType, "PENDING"));
        if (includeNews && (sourceType == null || sourceType.isBlank()
            || "NEWS".equalsIgnoreCase(sourceType) || "POLICY".equalsIgnoreCase(sourceType)))
            plan.add(step(4, "查询新闻与政策", "检索可能相关的外部背景，并检查行业、时间和对象是否适用",
                "NEWS/POLICY", "PENDING"));
        plan.add(step(plan.size() + 1, "交叉核对与引用", "区分数据事实和可能解释，逐句核对来源及原文位置",
            "只展示有证据的结论", "PENDING"));
        return plan;
    }

    /**
     * “全部来源”先取综合相关结果，再按资料类型补充证据，避免排名靠前的单一来源
     * 挤掉新闻或结构化报告。指定资料类型时仍严格只检索该类型。
     */
    private RetrievalResult retrieveEvidence(String question, String sourceType, List<Long> roleIds,
        boolean admin, boolean includeNews, List<Map<String, Object>> logs, QaProgressListener listener)
    {
        long started = System.nanoTime();
        List<KnowledgeChunk> metricChunks = sourceType == null || sourceType.isBlank() || "REPORT".equalsIgnoreCase(sourceType)
            ? ingestService.searchMetrics(question, roleIds, admin, 3) : List.of();
        if (metricChunks == null) metricChunks = List.of();
        logs.add(log("METRIC_SEARCH", "查询结构化指标库", question,
            metricChunks.size() + " 个指标候选", elapsedMillis(started), metricChunks.isEmpty() ? "NO_HIT" : "SUCCESS"));
        notifyProgress(listener, 16, "已查询结构化指标库", logs);
        Map<String, KnowledgeChunk> merged = new LinkedHashMap<>();
        mergeChunks(merged, metricChunks);
        if (sourceType != null && !sourceType.isBlank())
        {
            String query = "POLICY".equalsIgnoreCase(sourceType) ? policyContextQuery(question) : question;
            List<KnowledgeChunk> selected = timedSearch(query, sourceType, roleIds, admin, 8, logs);
            mergeChunks(merged, selected);
            notifyProgress(listener, 30, "已查询指定知识来源 " + sourceType, logs);
            return new RetrievalResult(merged.values().stream().limit(12).toList(), metricChunks.size());
        }

        mergeChunks(merged, timedSearch(question, "REPORT", roleIds, admin, 3, logs));
        notifyProgress(listener, 21, "已查询结构化报告", logs);
        if (includeNews)
        {
            mergeChunks(merged, timedSearch(question, "NEWS", roleIds, admin, 3, logs));
            notifyProgress(listener, 25, "已查询新闻库", logs);
            mergeChunks(merged, timedSearch(policyContextQuery(question), "POLICY", roleIds, admin, 3, logs));
            notifyProgress(listener, 30, "已查询政策库", logs);
        }
        mergeChunks(merged, timedSearch(question, "PDF", roleIds, admin, 2, logs));
        notifyProgress(listener, 33, "已查询固定PDF资料", logs);
        return new RetrievalResult(merged.values().stream().limit(10).toList(), metricChunks.size());
    }

    private List<KnowledgeChunk> timedSearch(String query, String type, List<Long> roleIds, boolean admin,
        int limit, List<Map<String, Object>> logs)
    {
        long started = System.nanoTime();
        List<KnowledgeChunk> found = ingestService.search(query, type, roleIds, admin, limit);
        if (found == null) found = List.of();
        logs.add(log(type + "_SEARCH", "查询" + sourceTypeLabel(type) + "库", query,
            found.size() + " 条证据", elapsedMillis(started), found.isEmpty() ? "NO_HIT" : "SUCCESS"));
        return found;
    }

    private void mergeChunks(Map<String, KnowledgeChunk> target, List<KnowledgeChunk> chunks)
    {
        if (chunks == null) return;
        for (KnowledgeChunk chunk : chunks)
        {
            if (chunk == null) continue;
            String key = chunk.getId() == null
                ? String.valueOf(chunk.getSourceId()) + ":" + chunk.getVersionId() + ":" + chunk.getChunkNo()
                : "ID:" + chunk.getId();
            target.putIfAbsent(key, chunk);
        }
    }

    private List<Map<String, Object>> queryPlan(String question, String sourceType, int metricHits, int verifiedClaims,
        boolean includeNews, Map<String, Integer> sourceBreakdown)
    {
        List<Map<String, Object>> plan = new ArrayList<>();
        plan.add(step(1, "理解你的问题", intentNarrative(question), questionProfile(question)));
        plan.add(step(2, "生成并执行检索", "先查确定性数据，再分别查询新闻、政策和固定资料",
            metricHits + " 个结构化指标，" + sourceBreakdown.get("REPORT") + " 条报告证据"));
        if (includeNews)
            plan.add(step(3, "核对外部解释", "检查新闻和政策与问题的行业、时间、对象是否匹配",
                sourceBreakdown.get("NEWS") + " 条新闻证据，" + sourceBreakdown.get("POLICY") + " 条政策证据"));
        plan.add(step(includeNews ? 4 : 3, "逐句核验结论", "确保事实句100%有来源，且可定位到原文段落",
            verifiedClaims + " 个事实句通过"));
        return plan;
    }

    private Map<String, Integer> sourceBreakdown(List<KnowledgeChunk> chunks)
    {
        Map<String, Integer> counts = new LinkedHashMap<>();
        counts.put("REPORT", 0); counts.put("NEWS", 0); counts.put("POLICY", 0); counts.put("PDF", 0); counts.put("OTHER", 0);
        for (KnowledgeChunk chunk : chunks)
        {
            String type = chunk.getSourceType() == null ? "OTHER" : chunk.getSourceType().toUpperCase();
            counts.put(type, counts.getOrDefault(type, 0) + 1);
        }
        return counts;
    }

    private Map<String, Object> step(int order, String name, String action, String input)
    {
        return step(order, name, action, input, "DONE");
    }

    private Map<String, Object> step(int order, String name, String action, String input, String status)
    {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("order", order); value.put("name", name); value.put("action", action); value.put("input", input); value.put("status", status);
        return value;
    }

    private String questionProfile(String question)
    {
        List<String> parts = new ArrayList<>();
        List<String> entities = KnowledgeTextProcessor.detectEntityTerms(question);
        if (!entities.isEmpty()) parts.add("主体=" + String.join("/", entities));
        Matcher time = TIME_PATTERN.matcher(question == null ? "" : question);
        if (time.find()) parts.add("时间=" + time.group(1));
        String value = question == null ? "" : question.toLowerCase();
        List<String> intents = new ArrayList<>();
        if (value.matches("(?s).*(出货|销量|面积|市占|份额|占比|同比|增长|shipment|share|yoy|area).*")) intents.add("数据结论");
        if (value.matches("(?s).*(原因|解释|背景|动作|新闻|政策|影响|为什么).*")) intents.add("新闻/政策解释");
        if (value.matches("(?s).*(对比|比较|差异|vs).*")) intents.add("对比分析");
        if (intents.isEmpty()) intents.add("知识检索");
        parts.add("意图=" + String.join("+", intents));
        return String.join("；", parts);
    }

    private String intentNarrative(String question)
    {
        List<String> entities = KnowledgeTextProcessor.detectEntityTerms(question);
        String subject = entities.isEmpty() ? "相关市场主体" : String.join("、", entities);
        Matcher time = TIME_PATTERN.matcher(question == null ? "" : question);
        String period = time.find() ? time.group(1) : "问题所述时间范围";
        String value = question == null ? "" : question.toLowerCase();
        String metric = value.matches("(?s).*(市占|份额|share).*") ? "市场份额"
            : value.matches("(?s).*(销量|销售).*") ? "销量"
            : value.matches("(?s).*(面积|area).*") ? "出货面积" : "出货及相关指标";
        boolean explanation = value.matches("(?s).*(原因|解释|背景|新闻|政策|影响|为什么).*" );
        return "用户想了解" + subject + "在" + period + "的" + metric
            + (explanation ? "，并判断新闻或政策是否可能解释该变化。" : "。")
            + "我需要先从结构化报告确认数值，再检索相关资料，最后逐句核对引用。";
    }

    /** 政策通常面向行业而非单一企业，不能强制要求正文出现企业名。 */
    private String policyContextQuery(String question)
    {
        String value = question == null ? "" : question.toLowerCase();
        if (value.matches("(?s).*(车载显示|ltps|a-si|座舱|显示面板).*"))
            return "车载显示 智能座舱 技术改造 产业支持 政策";
        if (value.matches("(?s).*(新能源|汽车|车辆|销量|销售|制造).*"))
            return "新能源汽车 汽车消费 以旧换新 制造 产业支持 政策";
        return "产业支持 技术改造 消费 供给 政策 " + (question == null ? "" : question);
    }

    private String sourceTypeLabel(String type)
    {
        return switch (type == null ? "" : type.toUpperCase()) {
            case "REPORT" -> "结构化报告"; case "NEWS" -> "新闻"; case "POLICY" -> "政策";
            case "PDF" -> "固定PDF资料"; default -> "知识";
        };
    }

    private List<Map<String, Object>> analysisTrace(String question, List<KnowledgeChunk> chunks,
        KnowledgeCitationValidator.ValidationResult validation, String answerMode)
    {
        List<Map<String, Object>> trace = new ArrayList<>();
        trace.add(traceStep(1, "我先理解问题", intentNarrative(question),
            "识别结果：" + questionProfile(question), "这一步只拆解任务，不产生业务结论。", List.of()));
        List<Map<String, Object>> dataEvidence = traceEvidence(chunks, List.of("REPORT", "PDF"), 4);
        trace.add(traceStep(2, "我先确认数据事实", "查询结构化指标、分析报告和固定PDF资料。",
            dataEvidence.isEmpty() ? "没有找到可用于数据结论的证据。"
                : "找到 " + dataEvidence.size() + " 条数据证据；数值结论只能来自这些资料。",
            "新闻和政策不能替代或修改这里的计算数值。", dataEvidence));
        List<Map<String, Object>> externalEvidence = traceEvidence(chunks, List.of("NEWS", "POLICY"), 4);
        trace.add(traceStep(3, "我再查可能的外部解释", "分别查询新闻和政策，并检查行业、时间和对象是否相关。",
            externalEvidence.isEmpty() ? "没有找到匹配的新闻或政策证据。"
                : "找到 " + externalEvidence.size() + " 条外部证据，继续与数据事实交叉核对。",
            "外部材料只能说明可能的背景，不能单独证明销量或出货增长。", externalEvidence));
        boolean canAssociate = !dataEvidence.isEmpty() && !externalEvidence.isEmpty();
        trace.add(traceStep(4, "我检查两类证据能否关联", "同时检查数据事实和外部背景是否都存在。",
            canAssociate ? "具备两类证据，可以表述为“可能有关”，但不能表述为确定因果。"
                : "证据类型不完整，不建立政策或新闻与数据变化之间的因果关联。",
            "政策适用不等于企业增长由政策导致；仍可能存在产品、客户和竞争等其他因素。",
            canAssociate ? traceEvidence(chunks, List.of("REPORT", "POLICY", "NEWS"), 4) : List.of()));
        List<Map<String, Object>> verified = new ArrayList<>();
        for (Map<String, Object> claim : validation.getClaims())
        {
            Object values = claim.get("evidences");
            if (values instanceof List<?>)
                for (Object value : (List<?>) values)
                    if (value instanceof Map<?, ?> && verified.size() < 6)
                        verified.add(new LinkedHashMap<>((Map<String, Object>) value));
        }
        trace.add(traceStep(5, "我逐句检查最终结论", "检查每个事实句的来源编号、数字、主体和原文位置。",
            validation.getClaims().size() + " 个事实句通过校验；回答模式为 " + answerMode + "。",
            "没有来源或无法精确定位的事实句不会返回。", verified));
        return trace;
    }

    private Map<String, Object> traceStep(int order, String title, String action, String result,
        String boundary, List<Map<String, Object>> evidences)
    {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("order", order); step.put("title", title); step.put("action", action);
        step.put("result", result); step.put("boundary", boundary); step.put("evidences", evidences);
        return step;
    }

    private List<Map<String, Object>> traceEvidence(List<KnowledgeChunk> chunks, List<String> types, int limit)
    {
        List<Map<String, Object>> values = new ArrayList<>();
        for (int i = 0; i < chunks.size() && values.size() < limit; i++)
        {
            KnowledgeChunk chunk = chunks.get(i);
            String type = chunk.getSourceType() == null ? "" : chunk.getSourceType().toUpperCase();
            if (!types.contains(type)) continue;
            String snippet = extractExcerpt(chunk.getContent(), 320);
            if (snippet.isBlank()) snippet = chunk.getContent() == null ? "" : abbreviate(chunk.getContent(), 320);
            int start = chunk.getContent() == null ? 0 : Math.max(0, chunk.getContent().indexOf(snippet));
            int end = Math.min(chunk.getContent() == null ? 0 : chunk.getContent().length(), start + snippet.length());
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("citationLabel", "S" + (i + 1)); evidence.put("chunkId", chunk.getId());
            evidence.put("sourceName", chunk.getSourceName()); evidence.put("sourceType", chunk.getSourceType());
            evidence.put("originalName", chunk.getOriginalName()); evidence.put("pageStart", chunk.getPageStart());
            evidence.put("sourceUrl", chunk.getSourceUrl()); evidence.put("evidenceSnippet", snippet);
            evidence.put("startOffset", start); evidence.put("endOffset", end);
            values.add(evidence);
        }
        return values;
    }

    private void notifyProgress(QaProgressListener listener, int progress, String stage,
        List<Map<String, Object>> logs)
    {
        if (listener == null) return;
        List<Map<String, Object>> snapshot = logs.stream()
            .<Map<String, Object>>map(row -> new LinkedHashMap<>(row)).toList();
        listener.onProgress(progress, stage, snapshot);
    }

    private Map<String, Object> log(String type, String action, String input, String result,
        long durationMs, String status)
    {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("type", type); value.put("action", action); value.put("input", input); value.put("result", result);
        value.put("durationMs", durationMs); value.put("status", status);
        return value;
    }

    private Map<String, Object> emptyGraph()
    {
        Map<String, Object> graph = new LinkedHashMap<>(); graph.put("nodes", List.of()); graph.put("links", List.of()); graph.put("categories", List.of()); return graph;
    }

    private String callLlm(String question, List<KnowledgeChunk> chunks, String repairInstruction) throws Exception
    {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++)
        {
            KnowledgeChunk chunk = chunks.get(i);
            String evidenceRole = "NEWS".equalsIgnoreCase(chunk.getSourceType()) ? "新闻解释证据"
                : "POLICY".equalsIgnoreCase(chunk.getSourceType()) ? "政策解释证据"
                : "REPORT".equalsIgnoreCase(chunk.getSourceType()) ? "分析结果证据" : "固定背景资料";
            context.append("[S").append(i + 1).append("] [").append(evidenceRole).append("] ")
                .append(chunk.getSourceName());
            if (chunk.getOriginalName() != null && !chunk.getOriginalName().isBlank())
                context.append(" / 原始文件：").append(chunk.getOriginalName());
            context.append(" / ").append(chunk.getVersionNo());
            if (chunk.getPageStart() != null) context.append(" / PDF第").append(chunk.getPageStart()).append("页");
            if (chunk.getMetricId() != null && !chunk.getMetricId().isBlank()) context.append(" / 指标").append(chunk.getMetricId());
            context.append('\n').append(chunk.getContent()).append("\n\n");
        }
        JSONArray messages = new JSONArray();
        messages.add(message("system", "你是市场分析知识库问答助手。只能依据用户提供的资料回答。必须先写“数据结论”，再在存在新闻或政策证据时写“外部解释”。数据结论中的销量、市占率、同比、尺寸和技术路线等数值，只能依据标记为[分析结果证据]或[固定背景资料]的来源，不得用新闻或政策估算、替换或修正。车载显示面板出货指标的thousand_units必须表述为“千片”或“Kpcs”，禁止写成“千台”。新闻只能解释已存在数据结论的可能背景和市场动作。政策只能在适用行业、时间和对象相符时作为可能影响因素，必须写“政策可能改善需求或供给环境”“增长可能与该政策有关”，禁止写成确定因果或唯一原因。每条政策解释必须同时引用数据来源和政策来源。若没有新闻或政策证据，明确说明未检索到，不得自行补充。每个事实句都必须在句末标注一个或多个来源编号，如[S1]或[S1][S2]；禁止多句话共用一个句末引用。引用必须直接支持该事实，数字必须与原文一致。使用短句或列表，不要使用Markdown表格。资料不足时明确说明，不得编造来源编号。"));
        String userPrompt = "问题：" + question + "\n\n可用资料：\n" + context;
        if (repairInstruction != null && !repairInstruction.isBlank())
            userPrompt += "\n\n修复要求：\n" + repairInstruction;
        messages.add(message("user", userPrompt));
        JSONObject payload = new JSONObject();
        payload.put("model", llmConfiguration.getModel()); payload.put("messages", messages); payload.put("temperature", 0.1); payload.put("stream", false);
        return sendLlmRequest(payload);
    }

    private String sendLlmRequest(JSONObject payload) throws Exception
    {
        HttpRequest request = HttpRequest.newBuilder(URI.create(llmConfiguration.getApiUrl())).timeout(Duration.ofSeconds(requestTimeoutSeconds))
            .header("Authorization", "Bearer " + llmConfiguration.getApiKey()).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toJSONString(), StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IllegalStateException("Ark API HTTP " + response.statusCode() + "：" + abbreviate(response.body(), 300));
        JSONObject root = JSONObject.parseObject(response.body());
        JSONArray choices = root.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) throw new IllegalStateException("Ark API未返回回答");
        String content = choices.getJSONObject(0).getJSONObject("message").getString("content");
        if (content == null || content.isBlank()) throw new IllegalStateException("Ark API返回空回答");
        return content.trim();
    }

    private String buildExtractiveFallback(String question, List<KnowledgeChunk> chunks)
    {
        StringBuilder answer = new StringBuilder("以下为通过引用校验的知识库确定性结果：\n");
        for (int i = 0; i < chunks.size(); i++)
        {
            List<String> metricLines = structuredMetricLines(question, chunks.get(i), i + 1);
            if (!metricLines.isEmpty())
            {
                answer.append("\n数据结论：\n");
                for (String line : metricLines) answer.append("- ").append(line).append('\n');
                appendNewsEvidence(answer, chunks, 3);
                return answer.toString().trim();
            }
        }

        int dataCount = appendEvidenceByType(answer, chunks, List.of("REPORT", "PDF"), 2, "数据与报告证据");
        int newsCount = appendEvidenceByType(answer, chunks, List.of("NEWS", "POLICY"), 3, "新闻与政策解释证据");
        if (dataCount == 0 && newsCount > 0)
        {
            int explanationStart = answer.indexOf("\n新闻与政策解释证据：");
            if (explanationStart >= 0)
                answer.insert(explanationStart, "\n现有资料不足以形成结构化数据结论：\n");
        }
        if (dataCount + newsCount == 0)
        {
            int otherCount = appendEvidenceByType(answer, chunks, List.of(), 3, "其他固定资料证据");
            if (otherCount == 0) throw new IllegalStateException("候选知识切片没有可返回的原文内容");
        }
        return answer.toString().trim();
    }

    private void appendNewsEvidence(StringBuilder answer, List<KnowledgeChunk> chunks, int limit)
    {
        appendEvidenceByType(answer, chunks, List.of("NEWS", "POLICY"), limit, "新闻与政策解释证据");
    }

    private int appendEvidenceByType(StringBuilder answer, List<KnowledgeChunk> chunks, List<String> sourceTypes,
        int limit, String title)
    {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < chunks.size() && lines.size() < limit; i++)
        {
            KnowledgeChunk chunk = chunks.get(i);
            String type = chunk.getSourceType() == null ? "" : chunk.getSourceType().toUpperCase();
            if (!sourceTypes.isEmpty() && !sourceTypes.contains(type)) continue;
            String excerpt = extractExcerpt(chunk.getContent(), 320);
            if (excerpt.isBlank()) continue;
            lines.add("- " + excerpt + " [S" + (i + 1) + "]");
        }
        if (!lines.isEmpty())
        {
            answer.append('\n').append(title).append("：\n");
            for (String line : lines) answer.append(line).append('\n');
        }
        return lines.size();
    }

    private List<String> structuredMetricLines(String question, KnowledgeChunk chunk, int sourceIndex)
    {
        if (chunk.getMetricId() == null || chunk.getMetricId().isBlank()
            || chunk.getMetricId().startsWith("dataset.")) return List.of();
        JSONObject metric;
        try { metric = JSONObject.parseObject(chunk.getContent()); }
        catch (Exception ignored) { return List.of(); }
        if (metric == null || !metric.containsKey("metric_id")) return List.of();
        String period = requestedPeriod(question);
        String label = readableMetricLabel(chunk.getMetricId());
        String periodLabel = readablePeriod(period);
        List<String> lines = new ArrayList<>();
        JSONObject periods = metric.getJSONObject("periods");
        String unit = metric.getString("unit");
        if (periods != null && periods.get(period) != null)
            lines.add(label + periodLabel + "为" + formatMetricValue(periods.get(period), unit)
                + "。[S" + sourceIndex + "]");
        JSONObject yoyPeriods = metric.getJSONObject("yoy_periods");
        if (yoyPeriods != null && yoyPeriods.get(period) != null)
            lines.add(label + periodLabel + "同比" + formatPercent(yoyPeriods.get(period))
                + "。[S" + sourceIndex + "]");
        return lines;
    }

    private String readableMetricLabel(String metricId)
    {
        String id = metricId == null ? "" : metricId;
        String lower = id.toLowerCase();
        String subject = lower.startsWith("tianma.") ? "Tianma "
            : lower.startsWith("auo.") ? "AUO "
            : lower.startsWith("boe.") ? "BOE "
            : lower.startsWith("csot.") ? "CSOT "
            : lower.startsWith("market.") ? "市场 " : "";
        String technology = lower.contains(".ltps.") ? "LTPS "
            : lower.contains(".a-si.") || lower.contains(".asi.") ? "a-Si " : "";
        String metric = lower.contains("display_area") || lower.contains("displayarea") ? "出货面积"
            : lower.contains("share") ? "市场份额" : lower.contains("shipment") ? "出货量" : "指标值";
        return subject + technology + metric;
    }

    private String readablePeriod(String period)
    {
        return switch (period) {
            case "Y22" -> "（2022年）"; case "Y23" -> "（2023年）";
            case "Y24" -> "（2024年）"; case "Y25Q1-Q3" -> "（2025年前三季度）";
            default -> "（2025年预测）";
        };
    }

    private String formatMetricValue(Object value, String unit)
    {
        double numeric = Double.parseDouble(String.valueOf(value));
        String formatted = Math.rint(numeric) == numeric
            ? String.format("%,.0f", numeric) : String.format("%,.2f", numeric);
        if ("thousand_units".equalsIgnoreCase(unit)) return formatted + " Kpcs（千片）";
        if ("percent".equalsIgnoreCase(unit) || "share".equalsIgnoreCase(unit))
            return String.format("%.1f%%", numeric * 100.0);
        return unit == null || unit.isBlank() ? formatted : formatted + " " + unit;
    }

    private String formatPercent(Object value)
    {
        double ratio = Double.parseDouble(String.valueOf(value));
        String direction = ratio < 0 ? "下降" : "增长";
        return direction + String.format("%.1f%%", Math.abs(ratio) * 100.0);
    }

    private String requestedPeriod(String question)
    {
        String value = question == null ? "" : question.toLowerCase();
        if (value.contains("前三季度") || value.contains("q1-q3") || value.contains("q1_q3")) return "Y25Q1-Q3";
        if (value.contains("2022") || value.contains("y22")) return "Y22";
        if (value.contains("2023") || value.contains("y23")) return "Y23";
        if (value.contains("2024") || value.contains("y24")) return "Y24";
        return "Y25F";
    }

    private String extractExcerpt(String content, int maxLength)
    {
        if (content == null) return "";
        String value = "";
        String firstLine = "";
        for (String rawLine : content.replace("\r", "").split("\n"))
        {
            String line = rawLine.replaceAll("\\s+", " ").trim();
            if (line.isBlank()) continue;
            if (firstLine.isBlank()) firstLine = line;
            if (line.startsWith("正文：") || line.startsWith("正文:")
                || line.startsWith("政策正文：") || line.startsWith("政策正文:"))
            {
                value = line;
                break;
            }
        }
        if (value.isBlank()) value = firstLine;
        if (value.isBlank()) return "";
        int scanLength = Math.min(value.length(), maxLength);
        int boundary = -1;
        for (int i = 0; i < scanLength; i++)
        {
            char marker = value.charAt(i);
            if (marker == '。' || marker == '；' || marker == ';' || marker == '！'
                || marker == '？' || marker == '!' || marker == '?')
            {
                boundary = i + 1;
                break;
            }
        }
        return value.substring(0, boundary > 0 ? boundary : scanLength).trim();
    }

    private long elapsedMillis(long started)
    {
        return Math.max(0L, (System.nanoTime() - started) / 1_000_000L);
    }

    private JSONObject message(String role, String content)
    {
        JSONObject value = new JSONObject(); value.put("role", role); value.put("content", content); return value;
    }

    private Map<String, Object> toCitation(String label, KnowledgeChunk chunk, List<Map<String, Object>> evidenceLocations)
    {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("citationLabel", label); value.put("id", chunk.getId());
        value.put("sourceName", chunk.getSourceName()); value.put("sourceType", chunk.getSourceType());
        value.put("originalName", chunk.getOriginalName());
        value.put("versionNo", chunk.getVersionNo()); value.put("pageStart", chunk.getPageStart());
        value.put("pageEnd", chunk.getPageEnd()); value.put("sourceUrl", chunk.getSourceUrl());
        value.put("reportId", chunk.getReportId()); value.put("metricId", chunk.getMetricId());
        value.put("sourceSnippet", chunk.getSourceSnippet()); value.put("evidenceJson", chunk.getEvidenceJson());
        value.put("evidenceLocations", evidenceLocations);
        return value;
    }

    private String abbreviate(String value, int max)
    {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private record RetrievalResult(List<KnowledgeChunk> chunks, int metricHits) { }

    @FunctionalInterface
    public interface QaProgressListener
    {
        void onProgress(int progress, String stage, List<Map<String, Object>> logs);
    }
}
