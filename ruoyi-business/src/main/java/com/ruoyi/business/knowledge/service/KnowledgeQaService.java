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
    private static final Pattern YEAR_PATTERN = Pattern.compile("(20\\d{2})");
    private static final Pattern MARKET_RANK_PATTERN = Pattern.compile(
        "\"对象\"\\s*:\\s*\"([^\"]{1,80})\"\\s*,\\s*\"销量/数值\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");
    private static final Pattern TREND_POINT_PATTERN = Pattern.compile(
        "\"时间\"\\s*:\\s*\"(20\\d{2})-(\\d{2})\"\\s*,\\s*\"数值\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)");
    private static final String WEB_SEARCH_LABEL = "以下结果为联网搜索而来，并非固定知识库内容。";

    private final KnowledgeIngestService ingestService;
    private final KnowledgeCitationValidator citationValidator = new KnowledgeCitationValidator();
    private final HttpClient httpClient;
    private final KnowledgeWebSearchClient webSearchClient;
    private final LlmRuntimeConfiguration llmConfiguration;
    private KnowledgeGraphService graphService;

    @Value("${business.knowledge.llm.timeout-seconds:300}")
    private int requestTimeoutSeconds = 300;

    @Autowired
    public KnowledgeQaService(KnowledgeIngestService ingestService, LlmRuntimeConfiguration llmConfiguration)
    {
        this(ingestService, llmConfiguration, null);
    }

    KnowledgeQaService(KnowledgeIngestService ingestService, LlmRuntimeConfiguration llmConfiguration,
        KnowledgeWebSearchClient webSearchClient)
    {
        this.ingestService = ingestService;
        this.llmConfiguration = llmConfiguration;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        this.webSearchClient = webSearchClient == null ? new KnowledgeWebSearchClient(httpClient) : webSearchClient;
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
        return ask(question, sourceType, roleIds, admin, includeNews, false, (progress, stage, logs) -> { });
    }

    public Map<String, Object> ask(String question, String sourceType, List<Long> roleIds, boolean admin,
        boolean includeNews, boolean webLlm) throws Exception
    {
        return ask(question, sourceType, roleIds, admin, includeNews, webLlm, (progress, stage, logs) -> { });
    }

    public Map<String, Object> ask(String question, String sourceType, List<Long> roleIds, boolean admin,
        boolean includeNews, QaProgressListener progressListener) throws Exception
    {
        return ask(question, sourceType, roleIds, admin, includeNews, false, progressListener);
    }

    public Map<String, Object> ask(String question, String sourceType, List<Long> roleIds, boolean admin,
        boolean includeNews, boolean webLlm, QaProgressListener progressListener) throws Exception
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
        List<KnowledgeChunk> chunks = filterVehicleSalesEvidence(actualQuestion,
            withSalesReportContext(actualQuestion, retrieval.chunks()));
        Map<String, Integer> sourceBreakdown = sourceBreakdown(chunks);
        logs.add(log("SEARCH", "混合证据检索", actualQuestion,
            chunks.size() + " 个候选切片（结构化指标 " + retrieval.metricHits() + " 个）",
            elapsedMillis(retrievalStarted), "SUCCESS"));
        notifyProgress(progressListener, 35, "已完成多源检索", logs);
        if (chunks.isEmpty() || !hasUsableVehicleSalesEvidence(actualQuestion, chunks))
        {
            if (!webLlm)
                throw new IllegalArgumentException("固定知识库没有可用资料。可在知识问答界面开启「联网大模型」后再试。");
            return answerFromWebSearch(actualQuestion, logs, progressListener, sourceBreakdown);
        }

        String answer;
        String answerMode = "LLM_VERIFIED";
        List<String> warnings = new ArrayList<>();
        KnowledgeCitationValidator.ValidationResult validation;
        notifyProgress(progressListener, 50, "正在生成回答", logs);
        if (llmConfiguration.getApiKey().isBlank())
        {
            answer = buildExtractiveFallback(actualQuestion, chunks);
            validation = softValidateOrAttach(answer, chunks);
            answerMode = "EXTRACTIVE_FALLBACK";
            warnings.add("未配置大模型 API Key，已根据检索资料整理回答");
            logs.add(log("GENERATE", "LLM生成", llmConfiguration.getModel(), "未配置密钥，使用资料整理回答", 0, "FALLBACK"));
            notifyProgress(progressListener, 80, "已生成资料整理回答", logs);
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
                notifyProgress(progressListener, 80, "回答已生成，正在整理参考来源", logs);
                validation = softValidateOrAttach(answer, chunks);
                if (validation.getClaims().isEmpty())
                    logs.add(log("VERIFY", "参考来源整理", "检索资料挂载",
                        validation.getCitedIndexes().size() + " 条资料可供查阅", 0, "SUCCESS"));
                else
                    logs.add(log("VERIFY", "参考来源整理", "句内引用+资料挂载",
                        validation.getClaims().size() + " 个事实句可核验", 0, "SUCCESS"));
                notifyProgress(progressListener, 90, "参考来源已整理", logs);
            }
            catch (Exception llmError)
            {
                answer = buildExtractiveFallback(actualQuestion, chunks);
                validation = softValidateOrAttach(answer, chunks);
                answerMode = "EXTRACTIVE_FALLBACK";
                String reason = llmResponded ? "LLM返回异常" : "LLM请求失败";
                warnings.add(reason + "，已根据检索资料整理回答：" + abbreviate(llmError.getMessage(), 180));
                logs.add(log("GENERATE", "安全降级", llmConfiguration.getModel(), reason + "；返回资料整理回答",
                    elapsedMillis(llmStarted), "FALLBACK"));
                notifyProgress(progressListener, 85, "LLM不可用，已生成资料整理回答", logs);
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
        result.put("citationCoveragePercent", validation.getClaims().isEmpty() ? 0 : 100);
        result.put("citationPolicy", "AGENT_GROUNDED");
        result.put("warnings", warnings);
        if (includeNews && sourceBreakdown.get("NEWS") == 0 && sourceBreakdown.get("POLICY") == 0)
            warnings.add("当前问题未检索到匹配新闻或政策，本次回答只使用分析结果和固定资料");
        result.put("newsExplanationEnabled", includeNews);
        result.put("webLlmEnabled", webLlm);
        result.put("sourceBreakdown", sourceBreakdown);
        result.put("queryPlan", queryPlan(actualQuestion, sourceType, retrieval.metricHits(),
            Math.max(validation.getClaims().size(), validation.getCitedIndexes().size()), includeNews, sourceBreakdown));
        result.put("retrievalLogs", logs);
        result.put("analysisTrace", analysisTrace(actualQuestion, chunks, validation, answerMode));
        result.put("graph", graphService == null ? emptyGraph() : graphService.graphForChunks(citedChunks, roleIds, admin));
        if (webLlm)
            appendWebSearchSupplement(actualQuestion, result, logs, progressListener);
        notifyProgress(progressListener, 100, "问答与来源图谱生成完成", logs);
        return result;
    }

    private List<KnowledgeChunk> withMetricContext(List<KnowledgeChunk> chunks)
    {
        if (chunks == null || chunks.isEmpty()) return List.of();
        try
        {
            List<KnowledgeChunk> expanded = ingestService.expandMetricFragments(chunks);
            if (expanded == null || expanded.isEmpty()) expanded = chunks;
            return expanded;
        }
        catch (Exception ignored)
        {
            return chunks;
        }
    }

    /** 点名车企问销量时，把同报告的 fact_pack / 月度 JSON 一并挂上。 */
    private List<KnowledgeChunk> withSalesReportContext(String question, List<KnowledgeChunk> chunks)
    {
        List<KnowledgeChunk> base = withMetricContext(chunks);
        if (!requiresNamedSalesEvidence(question)) return base;
        try
        {
            List<KnowledgeChunk> expanded = ingestService.expandVehicleSalesReportContext(base);
            return expanded == null || expanded.isEmpty() ? base : expanded;
        }
        catch (Exception ignored)
        {
            return base;
        }
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
            if (!requiresNamedSalesEvidence(question) || wantsPolicyExplanation(question))
            {
                mergeChunks(merged, timedSearch(policyContextQuery(question), "POLICY", roleIds, admin, 3, logs));
                notifyProgress(listener, 30, "已查询政策库", logs);
            }
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
        plan.add(step(includeNews ? 4 : 3, "整理回答与参考来源", "用自然语言给出结论，并把检索资料挂到引用来源",
            verifiedClaims + " 条可参考资料"));
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
        String period = periodLabel(question);
        if (!period.isBlank()) parts.add("时间=" + period);
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
        String period = periodLabel(question);
        if (period.isBlank()) period = "问题所述时间范围";
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
        List<Map<String, Object>> dataEvidence = traceEvidence(chunks, List.of("REPORT", "PDF"), 4, question);
        trace.add(traceStep(2, "我先确认数据事实", "查询结构化指标、分析报告和固定PDF资料。",
            dataEvidence.isEmpty() ? "没有找到可用于数据结论的证据。"
                : "找到 " + dataEvidence.size() + " 条数据证据；数值结论只能来自这些资料。",
            "新闻和政策不能替代或修改这里的计算数值。", dataEvidence));
        List<Map<String, Object>> externalEvidence = traceEvidence(chunks, List.of("NEWS", "POLICY"), 4, question);
        trace.add(traceStep(3, "我再查可能的外部解释", "分别查询新闻和政策，并检查行业、时间和对象是否相关。",
            externalEvidence.isEmpty() ? "没有找到匹配的新闻或政策证据。"
                : "找到 " + externalEvidence.size() + " 条外部证据，继续与数据事实交叉核对。",
            "外部材料只能说明可能的背景，不能单独证明销量或出货增长。", externalEvidence));
        boolean canAssociate = !dataEvidence.isEmpty() && !externalEvidence.isEmpty();
        trace.add(traceStep(4, "我检查两类证据能否关联", "同时检查数据事实和外部背景是否都存在。",
            canAssociate ? "具备两类证据，可以表述为“可能有关”，但不能表述为确定因果。"
                : "证据类型不完整，不建立政策或新闻与数据变化之间的因果关联。",
            "政策适用不等于企业增长由政策导致；仍可能存在产品、客户和竞争等其他因素。",
            canAssociate ? traceEvidence(chunks, List.of("REPORT", "POLICY", "NEWS"), 4, question) : List.of()));
        List<Map<String, Object>> verified = new ArrayList<>();
        for (Map<String, Object> claim : validation.getClaims())
        {
            Object values = claim.get("evidences");
            if (values instanceof List<?>)
                for (Object value : (List<?>) values)
                    if (value instanceof Map<?, ?> && verified.size() < 6)
                        verified.add(new LinkedHashMap<>((Map<String, Object>) value));
        }
        trace.add(traceStep(5, "我整理最终回答", "按市面分析助手风格给出自然语言结论，并把检索资料挂到引用来源。",
            validation.getClaims().isEmpty()
                ? "回答模式为 " + answerMode + "；下方引用来源共 " + validation.getCitedIndexes().size() + " 条。"
                : validation.getClaims().size() + " 个事实句可核验；回答模式为 " + answerMode + "。",
            "思考链路保留证据拆解；最终结论不再使用固定“数据结论/外部解释”模板。", verified));
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

    private List<Map<String, Object>> traceEvidence(List<KnowledgeChunk> chunks, List<String> types, int limit,
        String question)
    {
        List<Map<String, Object>> values = new ArrayList<>();
        for (int i = 0; i < chunks.size() && values.size() < limit; i++)
        {
            KnowledgeChunk chunk = chunks.get(i);
            String type = chunk.getSourceType() == null ? "" : chunk.getSourceType().toUpperCase();
            if (!types.contains(type)) continue;
            String snippet = extractExcerpt(chunk.getContent(), question, 320);
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

    private Map<String, Object> answerFromWebSearch(String question, List<Map<String, Object>> logs,
        QaProgressListener progressListener, Map<String, Integer> sourceBreakdown) throws Exception
    {
        notifyProgress(progressListener, 45, "固定知识库没有相关内容，正在联网搜索", logs);
        long started = System.nanoTime();
        List<KnowledgeWebSearchClient.Hit> hits = webSearchClient.search(question);
        if (hits.isEmpty())
        {
            logs.add(log("SEARCH", "联网搜索", question, "没有返回可用网页", elapsedMillis(started), "FALLBACK"));
            throw new IllegalArgumentException("固定知识库没有相关内容，联网搜索也没有返回可用结果");
        }
        logs.add(log("SEARCH", "联网搜索", question, hits.size() + " 条网页结果", elapsedMillis(started), "SUCCESS"));
        notifyProgress(progressListener, 70, "已取得联网搜索结果，正在整理回答", logs);
        String answer;
        List<String> warnings = new ArrayList<>();
        warnings.add("固定知识库没有相关内容，以下结果为联网搜索而来");
        if (llmConfiguration.getApiKey().isBlank())
        {
            answer = formatWebSnippets(hits);
            warnings.add("未配置大模型 API Key，已直接整理联网搜索摘要");
            logs.add(log("GENERATE", "联网搜索整理", llmConfiguration.getModel(), "未配置密钥，直接返回网页摘要", 0, "FALLBACK"));
        }
        else
        {
            long llmStarted = System.nanoTime();
            try
            {
                answer = callWebLlm(question, hits);
                logs.add(log("GENERATE", "联网搜索整理", llmConfiguration.getModel(), "已根据网页结果生成回答",
                    elapsedMillis(llmStarted), "SUCCESS"));
            }
            catch (Exception llmError)
            {
                answer = formatWebSnippets(hits);
                warnings.add("大模型整理失败，已直接返回联网搜索摘要：" + abbreviate(llmError.getMessage(), 160));
                logs.add(log("GENERATE", "联网搜索整理", llmConfiguration.getModel(),
                    abbreviate(llmError.getMessage(), 160), elapsedMillis(llmStarted), "FALLBACK"));
            }
        }
        answer = ensureWebSearchLabel(answer);
        List<Map<String, Object>> citations = new ArrayList<>();
        for (int i = 0; i < hits.size(); i++) citations.add(toWebCitation("W" + (i + 1), hits.get(i)));
        List<Map<String, Object>> plan = new ArrayList<>();
        plan.add(step(1, "理解你的问题", intentNarrative(question), questionProfile(question)));
        plan.add(step(2, "检索固定知识库", "当前有效知识版本没有可用资料", "0 条证据"));
        plan.add(step(3, "联网搜索", "使用公开网页补充回答，并标明结果来自联网搜索", hits.size() + " 条网页结果"));
        List<Map<String, Object>> trace = new ArrayList<>();
        trace.add(traceStep(1, "固定知识库没有命中", "已检索结构化指标、分析报告、新闻、政策和PDF。",
            "没有可用于回答的知识切片。", "这一步不产生业务结论。", List.of()));
        trace.add(traceStep(2, "改为联网搜索", "根据问题检索公开网页，并整理成回答。",
            WEB_SEARCH_LABEL, "网页内容不经过知识库引用校验。", List.of()));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", answer);
        result.put("claims", List.of());
        result.put("citations", citations);
        result.put("model", llmConfiguration.getModel());
        result.put("answerMode", "WEB_SEARCH");
        result.put("citationCoveragePercent", 0);
        result.put("citationPolicy", "WEB_SEARCH");
        result.put("warnings", warnings);
        result.put("newsExplanationEnabled", false);
        result.put("webLlmEnabled", true);
        result.put("sourceBreakdown", sourceBreakdown);
        result.put("queryPlan", plan);
        result.put("retrievalLogs", logs);
        result.put("analysisTrace", trace);
        result.put("graph", emptyGraph());
        notifyProgress(progressListener, 100, "联网搜索回答已生成", logs);
        return result;
    }

    private String callWebLlm(String question, List<KnowledgeWebSearchClient.Hit> hits) throws Exception
    {
        return callWebLlm(question, hits, "");
    }

    private String callWebLlm(String question, List<KnowledgeWebSearchClient.Hit> hits, String existingAnswer) throws Exception
    {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < hits.size(); i++)
        {
            KnowledgeWebSearchClient.Hit hit = hits.get(i);
            context.append("[W").append(i + 1).append("] ").append(hit.title()).append('\n')
                .append(hit.url()).append('\n').append(hit.snippet()).append("\n\n");
        }
        boolean assist = existingAnswer != null && !existingAnswer.isBlank();
        JSONArray messages = new JSONArray();
        messages.add(message("system", assist
            ? "知识库已经给出一段回答。请只根据联网搜索摘要做辅助分析，用来对照公开口径。"
                + "不要改写或否定知识库里已经写出的数字。回答必须以这一句开头：" + WEB_SEARCH_LABEL
                + "采用摘要中的数字时，在句末标注对应编号，例如[W1]。不要编造摘要里没有的数字。不要使用Markdown表格。"
            : "固定知识库没有相关资料。请只根据给出的联网搜索摘要回答。"
                + "回答必须以这一句开头：" + WEB_SEARCH_LABEL
                + "采用摘要中的数字时，在句末标注对应编号，例如[W1]。不要编造摘要里没有的数字。摘要不足时直接说明。不要使用Markdown表格。"));
        messages.add(message("user", assist
            ? "问题：" + question + "\n\n知识库已有回答：\n" + existingAnswer + "\n\n联网搜索结果：\n" + context
            : "问题：" + question + "\n\n联网搜索结果：\n" + context));
        JSONObject payload = new JSONObject();
        payload.put("model", llmConfiguration.getModel());
        payload.put("messages", messages);
        payload.put("temperature", 0.2);
        payload.put("stream", false);
        return sendLlmRequest(payload);
    }

    private String formatWebSnippets(List<KnowledgeWebSearchClient.Hit> hits)
    {
        StringBuilder answer = new StringBuilder(WEB_SEARCH_LABEL).append("\n\n");
        for (int i = 0; i < hits.size(); i++)
        {
            KnowledgeWebSearchClient.Hit hit = hits.get(i);
            answer.append(i + 1).append(". ").append(hit.title()).append('\n');
            if (!hit.snippet().isBlank()) answer.append(hit.snippet()).append(' ');
            answer.append("[W").append(i + 1).append("]\n");
            answer.append(hit.url()).append("\n\n");
        }
        return answer.toString().trim();
    }

    @SuppressWarnings("unchecked")
    private void appendWebSearchSupplement(String question, Map<String, Object> result,
        List<Map<String, Object>> logs, QaProgressListener progressListener)
    {
        notifyProgress(progressListener, 92, "已启用联网大模型，正在联网搜索并辅助分析", logs);
        long started = System.nanoTime();
        List<KnowledgeWebSearchClient.Hit> hits;
        try
        {
            hits = webSearchClient.search(question);
        }
        catch (Exception error)
        {
            logs.add(log("SEARCH", "联网搜索", question, abbreviate(error.getMessage(), 160), elapsedMillis(started), "FALLBACK"));
            addWarning(result, "已启用联网大模型，但联网搜索失败：" + abbreviate(error.getMessage(), 120));
            return;
        }
        if (hits == null || hits.isEmpty())
        {
            logs.add(log("SEARCH", "联网搜索", question, "没有返回可用网页", elapsedMillis(started), "FALLBACK"));
            addWarning(result, "已启用联网大模型，但没有检索到可用网页");
            return;
        }
        logs.add(log("SEARCH", "联网搜索", question, hits.size() + " 条网页结果", elapsedMillis(started), "SUCCESS"));
        String existing = String.valueOf(result.getOrDefault("answer", ""));
        String webAnswer;
        if (llmConfiguration.getApiKey().isBlank())
        {
            webAnswer = formatWebSnippets(hits);
            logs.add(log("GENERATE", "联网辅助分析", llmConfiguration.getModel(), "未配置密钥，直接整理网页摘要", 0, "FALLBACK"));
        }
        else
        {
            long llmStarted = System.nanoTime();
            try
            {
                webAnswer = callWebLlm(question, hits, existing);
                logs.add(log("GENERATE", "联网辅助分析", llmConfiguration.getModel(), "已根据网页结果补充分析",
                    elapsedMillis(llmStarted), "SUCCESS"));
            }
            catch (Exception error)
            {
                webAnswer = formatWebSnippets(hits);
                logs.add(log("GENERATE", "联网辅助分析", llmConfiguration.getModel(),
                    abbreviate(error.getMessage(), 160), elapsedMillis(llmStarted), "FALLBACK"));
            }
        }
        webAnswer = ensureWebSearchLabel(webAnswer);
        result.put("answer", existing.isBlank() ? webAnswer : existing + "\n\n" + webAnswer);
        Object citationValue = result.get("citations");
        List<Map<String, Object>> citations = citationValue instanceof List<?>
            ? (List<Map<String, Object>>) citationValue : new ArrayList<>();
        if (!(citationValue instanceof List<?>)) result.put("citations", citations);
        for (int i = 0; i < hits.size(); i++) citations.add(toWebCitation("W" + (i + 1), hits.get(i)));
        result.put("answerMode", "KB_AND_WEB");
        addWarning(result, "已启用联网大模型，联网搜索结果用于辅助分析，并已单独标明来源");
        Object planValue = result.get("queryPlan");
        if (planValue instanceof List<?>)
        {
            List<Map<String, Object>> plan = (List<Map<String, Object>>) planValue;
            plan.add(step(plan.size() + 1, "联网辅助分析", "知识库已有结果后，再用联网搜索对照公开资料",
                hits.size() + " 条网页结果"));
        }
        Object traceValue = result.get("analysisTrace");
        if (traceValue instanceof List<?>)
        {
            List<Map<String, Object>> trace = (List<Map<String, Object>>) traceValue;
            trace.add(traceStep(trace.size() + 1, "联网搜索辅助分析",
                "在知识库结论之外检索公开网页，并交给大模型做对照分析。",
                WEB_SEARCH_LABEL, "网页内容单独标明，不改写知识库里已写出的数字。", List.of()));
        }
        notifyProgress(progressListener, 98, "联网辅助分析已并入回答", logs);
    }

    @SuppressWarnings("unchecked")
    private void addWarning(Map<String, Object> result, String warning)
    {
        Object value = result.get("warnings");
        List<String> warnings;
        if (value instanceof List<?>) warnings = (List<String>) value;
        else
        {
            warnings = new ArrayList<>();
            result.put("warnings", warnings);
        }
        warnings.add(warning);
    }

    private String ensureWebSearchLabel(String answer)
    {
        String text = answer == null ? "" : answer.trim();
        if (text.startsWith("以下结果为联网搜索而来")) return text;
        return WEB_SEARCH_LABEL + "\n\n" + text;
    }

    private Map<String, Object> toWebCitation(String label, KnowledgeWebSearchClient.Hit hit)
    {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("citationLabel", label);
        value.put("id", hit.url());
        value.put("sourceName", hit.title());
        value.put("sourceType", "WEB");
        value.put("originalName", hit.url());
        value.put("versionNo", "网页");
        value.put("sourceUrl", hit.url());
        value.put("sourceSnippet", hit.snippet());
        value.put("evidenceLocations", List.of());
        return value;
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
            context.append('\n').append(readableEvidenceContent(question, chunk)).append("\n\n");
        }
        JSONArray messages = new JSONArray();
        messages.add(message("system", "你是汽车行业市场洞察分析助手。回答必须严格基于用户提供的资料。"
            + "用自然语言直接回答，先给结论，再补充关键数据和必要限定，语气接近市面常见分析助手。"
            + "不要使用“数据结论”“外部解释”“新闻与政策解释证据”“以下为通过引用校验的知识库确定性结果”这类固定标题或模板。"
            + "销量、市占率、同比等数值只能引用分析结果或固定资料中的数字，不得用新闻或政策估算、替换或修正。"
            + "车载显示面板出货指标的thousand_units必须表述为“千片”或“Kpcs”，禁止写成“千台”。"
            + "新闻和政策只能作为可能背景，表述为“可能有关”，不要写成确定因果。"
            + "资料不足时直接说明缺什么，不要编造。可选用 [S1] 标注关键数字来源，但不是必须。"
            + "不要使用Markdown表格。"));
        String userPrompt = "问题：" + question + "\n\n可用资料：\n" + context;
        if (repairInstruction != null && !repairInstruction.isBlank())
            userPrompt += "\n\n补充要求：\n" + repairInstruction;
        messages.add(message("user", userPrompt));
        JSONObject payload = new JSONObject();
        payload.put("model", llmConfiguration.getModel()); payload.put("messages", messages); payload.put("temperature", 0.2); payload.put("stream", false);
        return sendLlmRequest(payload);
    }

    private KnowledgeCitationValidator.ValidationResult softValidateOrAttach(String answer, List<KnowledgeChunk> chunks)
    {
        try
        {
            return citationValidator.validate(answer, chunks);
        }
        catch (Exception ignored)
        {
            return KnowledgeCitationValidator.ValidationResult.attachRetrieved(chunks);
        }
    }

    private String sendLlmRequest(JSONObject payload) throws Exception
    {
        HttpRequest request = HttpRequest.newBuilder(URI.create(llmConfiguration.getApiUrl())).timeout(Duration.ofSeconds(requestTimeoutSeconds))
            .header("Authorization", "Bearer " + llmConfiguration.getApiKey()).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toJSONString(), StandardCharsets.UTF_8)).build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IllegalStateException("LLM API HTTP " + response.statusCode() + "：" + abbreviate(response.body(), 300));
        JSONObject root = JSONObject.parseObject(response.body());
        JSONArray choices = root.getJSONArray("choices");
        if (choices == null || choices.isEmpty()) throw new IllegalStateException("LLM API未返回回答");
        String content = choices.getJSONObject(0).getJSONObject("message").getString("content");
        if (content == null || content.isBlank()) throw new IllegalStateException("LLM API返回空回答");
        return content.trim();
    }

    private String buildExtractiveFallback(String question, List<KnowledgeChunk> chunks)
    {
        StringBuilder answer = new StringBuilder();
        if (requiresNamedSalesEvidence(question))
        {
            List<String> salesLines = collectVehicleSalesConclusions(question, chunks);
            if (!salesLines.isEmpty())
            {
                answer.append("根据现有资料，");
                for (int i = 0; i < salesLines.size(); i++)
                {
                    String line = stripCitationLabel(salesLines.get(i));
                    if (i == 0) answer.append(ensureSentence(line));
                    else answer.append(ensureSentence(line));
                    String citation = citationTail(salesLines.get(i));
                    if (!citation.isBlank()) answer.append(citation);
                    answer.append(i < salesLines.size() - 1 ? " " : "");
                }
                answer.append('\n');
                for (String missing : missingAskedYearNotes(question, salesLines))
                    answer.append(missing).append('\n');
                appendNaturalNewsContext(answer, chunks, 2, question);
                return answer.toString().trim();
            }
        }
        for (int i = 0; i < chunks.size(); i++)
        {
            List<String> metricLines = structuredMetricLines(question, chunks.get(i), i + 1);
            if (!metricLines.isEmpty())
            {
                answer.append("根据现有资料，");
                for (int j = 0; j < metricLines.size(); j++)
                {
                    String line = stripCitationLabel(metricLines.get(j));
                    answer.append(ensureSentence(line));
                    String citation = citationTail(metricLines.get(j));
                    if (!citation.isBlank()) answer.append(citation);
                    if (j < metricLines.size() - 1) answer.append(' ');
                }
                answer.append('\n');
                appendNaturalNewsContext(answer, chunks, 2, question);
                return answer.toString().trim();
            }
        }

        List<String> reportBits = collectEvidenceSnippets(chunks, List.of("REPORT", "PDF"), 2, question);
        List<String> newsBits = collectEvidenceSnippets(chunks, List.of("NEWS", "POLICY"), 3, question);
        if (!reportBits.isEmpty())
        {
            answer.append("根据现有资料，");
            for (int i = 0; i < reportBits.size(); i++)
            {
                answer.append(ensureSentence(stripCitationLabel(reportBits.get(i))));
                answer.append(citationTail(reportBits.get(i)));
                if (i < reportBits.size() - 1) answer.append(' ');
            }
            answer.append('\n');
        }
        else if (!newsBits.isEmpty())
            answer.append("现有资料里没有足够的结构化数据结论，先根据相关新闻整理如下。\n");
        if (!newsBits.isEmpty())
        {
            if (!reportBits.isEmpty()) answer.append("相关新闻或政策背景：");
            for (int i = 0; i < newsBits.size(); i++)
            {
                answer.append(ensureSentence(stripCitationLabel(newsBits.get(i))));
                answer.append(citationTail(newsBits.get(i)));
                if (i < newsBits.size() - 1) answer.append(' ');
            }
            answer.append('\n');
        }
        if (reportBits.isEmpty() && newsBits.isEmpty())
        {
            List<String> other = collectEvidenceSnippets(chunks, List.of(), 3, question);
            if (other.isEmpty()) throw new IllegalStateException("候选知识切片没有可返回的原文内容");
            answer.append("根据现有资料，");
            for (int i = 0; i < other.size(); i++)
            {
                answer.append(ensureSentence(stripCitationLabel(other.get(i))));
                answer.append(citationTail(other.get(i)));
                if (i < other.size() - 1) answer.append(' ');
            }
        }
        return answer.toString().trim();
    }

    private void appendNaturalNewsContext(StringBuilder answer, List<KnowledgeChunk> chunks, int limit, String question)
    {
        List<String> newsBits = collectEvidenceSnippets(chunks, List.of("NEWS", "POLICY"), limit, question);
        if (newsBits.isEmpty()) return;
        answer.append("相关新闻或政策背景：");
        for (int i = 0; i < newsBits.size(); i++)
        {
            answer.append(ensureSentence(stripCitationLabel(newsBits.get(i))));
            answer.append(citationTail(newsBits.get(i)));
            if (i < newsBits.size() - 1) answer.append(' ');
        }
        answer.append('\n');
    }

    private List<String> collectEvidenceSnippets(List<KnowledgeChunk> chunks, List<String> sourceTypes,
        int limit, String question)
    {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < chunks.size() && lines.size() < limit; i++)
        {
            KnowledgeChunk chunk = chunks.get(i);
            String type = chunk.getSourceType() == null ? "" : chunk.getSourceType().toUpperCase();
            if (!sourceTypes.isEmpty() && !sourceTypes.contains(type)) continue;
            String excerpt = extractExcerpt(chunk.getContent(), question, 320);
            if (excerpt.isBlank()) continue;
            lines.add(excerpt + " [S" + (i + 1) + "]");
        }
        return lines;
    }

    private String stripCitationLabel(String line)
    {
        if (line == null) return "";
        return line.replaceAll("\\s*\\[S\\d+]$", "").trim();
    }

    private String citationTail(String line)
    {
        if (line == null) return "";
        java.util.regex.Matcher matcher = Pattern.compile("(\\[S\\d+])\\s*$").matcher(line.trim());
        return matcher.find() ? matcher.group(1) : "";
    }

    private String ensureSentence(String text)
    {
        String value = text == null ? "" : text.trim();
        if (value.isBlank()) return value;
        if (value.endsWith("。") || value.endsWith("！") || value.endsWith("？")
            || value.endsWith(".") || value.endsWith("!") || value.endsWith("?")) return value;
        return value + "。";
    }

    private List<String> structuredMetricLines(String question, KnowledgeChunk chunk, int sourceIndex)
    {
        if (chunk.getMetricId() == null || chunk.getMetricId().isBlank()
            || chunk.getMetricId().startsWith("dataset.")) return List.of();
        if (requiresNamedSalesEvidence(question) && isDisplayPanelMetric(chunk.getMetricId())) return List.of();
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
            case "Y25F" -> "（2025年预测）"; case "Y26" -> "（2026年）";
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
        if (yearsIn(question).size() > 1) return "MULTI";
        if (value.contains("2022") || value.contains("y22")) return "Y22";
        if (value.contains("2023") || value.contains("y23")) return "Y23";
        if (value.contains("2024") || value.contains("y24")) return "Y24";
        if (value.contains("2026") || value.contains("y26")) return "Y26";
        return "Y25F";
    }

    /** 问题里出现的全部时间都要保留，不能只取第一个年份。 */
    private String periodLabel(String question)
    {
        Matcher time = TIME_PATTERN.matcher(question == null ? "" : question);
        List<String> periods = new ArrayList<>();
        while (time.find())
        {
            String hit = time.group(1).replaceAll("\\s+", " ").trim();
            if (!hit.isBlank() && !periods.contains(hit)) periods.add(hit);
        }
        return periods.size() <= 1 ? (periods.isEmpty() ? "" : periods.get(0)) : String.join("和", periods);
    }

    /** 点名车企问销量时，不能把面板出货、充电网络或泛化政策当成销量证据。 */
    private List<KnowledgeChunk> filterVehicleSalesEvidence(String question, List<KnowledgeChunk> chunks)
    {
        if (!requiresNamedSalesEvidence(question) || chunks == null) return chunks == null ? List.of() : chunks;
        List<KnowledgeChunk> kept = new ArrayList<>();
        for (KnowledgeChunk chunk : chunks)
            if (chunk != null && keepForVehicleSales(question, chunk)) kept.add(chunk);
        return kept;
    }

    private boolean requiresNamedSalesEvidence(String question)
    {
        String value = question == null ? "" : question.toLowerCase();
        boolean sales = value.contains("销量") || value.contains("销售");
        boolean display = value.contains("出货") || value.contains("面板") || value.contains("hud")
            || value.contains("显示");
        return sales && !display && !KnowledgeTextProcessor.detectEntityTerms(question).isEmpty();
    }

    private boolean wantsPolicyExplanation(String question)
    {
        String value = question == null ? "" : question.toLowerCase();
        return value.matches("(?s).*(政策|影响|原因|解释|背景|为什么).*");
    }

    private boolean keepForVehicleSales(String question, KnowledgeChunk chunk)
    {
        if (!extractVehicleSalesFacts(chunk, question).isEmpty()) return true;
        String metricId = chunk.getMetricId() == null ? "" : chunk.getMetricId().toLowerCase();
        if (isDisplayPanelMetric(metricId)) return false;
        String haystack = (metricId + " " + safe(chunk.getTitlePath()) + " " + safe(chunk.getSourceName())
            + " " + safe(chunk.getOriginalName()) + " " + safe(chunk.getContent())).toLowerCase();
        List<String> years = yearsIn(question);
        if (!years.isEmpty() && years.stream().noneMatch(haystack::contains)) return false;
        boolean brand = KnowledgeTextProcessor.detectEntityTerms(question).stream()
            .anyMatch(term -> haystack.contains(term.toLowerCase()));
        if (!brand) return false;
        if (hasUsableMarketSalesJson(chunk.getContent())) return true;
        if (isProvenanceOnlyMarketJson(chunk.getContent())) return false;
        return haystack.contains("销量") || haystack.contains("销售") || haystack.contains("万辆")
            || haystack.contains("wholesale") || haystack.contains("retail_sales")
            || haystack.contains("monthly_trend");
    }

    /** 点名年份问销量时，没有命中这些年份的新闻不能当作可用证据，应改走联网搜索。 */
    private boolean hasUsableVehicleSalesEvidence(String question, List<KnowledgeChunk> chunks)
    {
        if (!requiresNamedSalesEvidence(question)) return true;
        if (chunks == null || chunks.isEmpty()) return false;
        if (yearsIn(question).isEmpty()) return true;
        return !collectVehicleSalesConclusions(question, chunks).isEmpty();
    }

    private boolean isDisplayPanelMetric(String metricId)
    {
        String id = metricId == null ? "" : metricId.toLowerCase();
        if (id.startsWith("market.")) return false;
        return id.contains("hud") || id.contains("adayo") || id.contains("display")
            || id.contains("panel") || id.contains("ltps") || id.contains("a-si") || id.contains(".asi.");
    }

    private String readableEvidenceContent(String question, KnowledgeChunk chunk)
    {
        if (!requiresNamedSalesEvidence(question)) return safe(chunk.getContent());
        List<String> facts = extractVehicleSalesFacts(chunk, question);
        if (!facts.isEmpty()) return String.join("\n", facts);
        if (isProvenanceOnlyMarketJson(chunk.getContent()))
            return "（该切片为整车分析溯源元数据，未解析出与问题主体匹配的可读销量结论）";
        if (looksLikeJsonNoise(chunk.getContent()) && !hasUsableMarketSalesJson(chunk.getContent()))
            return "（该切片为整车分析结构化JSON，未解析出与问题主体匹配的可读销量结论）";
        return safe(chunk.getContent());
    }

    private List<String> collectVehicleSalesConclusions(String question, List<KnowledgeChunk> chunks)
    {
        List<ScoredFact> scored = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++)
        {
            KnowledgeChunk chunk = chunks.get(i);
            String type = chunk.getSourceType() == null ? "" : chunk.getSourceType().toUpperCase();
            if (!type.isBlank() && !List.of("REPORT", "PDF", "NEWS").contains(type)) continue;
            for (String fact : extractVehicleSalesFacts(chunk, question))
            {
                int score = scoreSalesSentence(fact, KnowledgeTextProcessor.detectEntityTerms(question), question);
                if ("REPORT".equals(type) || "PDF".equals(type)) score += 3;
                scored.add(new ScoredFact(score, fact + " [S" + (i + 1) + "]"));
            }
        }
        scored.sort((a, b) -> Integer.compare(b.score(), a.score()));
        List<String> lines = new ArrayList<>();
        for (ScoredFact item : scored)
        {
            if (item.score() <= 0) continue;
            boolean duplicate = lines.stream().anyMatch(existing -> normalizeFactKey(existing).equals(normalizeFactKey(item.line())));
            if (duplicate) continue;
            lines.add(item.line());
            if (lines.size() >= 5) break;
        }
        return lines;
    }

    private List<String> missingAskedYearNotes(String question, List<String> salesLines)
    {
        List<String> asked = yearsIn(question);
        if (asked.size() < 2) return List.of();
        String joined = String.join(" ", salesLines);
        List<String> notes = new ArrayList<>();
        List<String> entities = KnowledgeTextProcessor.detectEntityTerms(question);
        String subject = entities.isEmpty() ? "该主体" : entities.get(0);
        for (String year : asked)
            if (!joined.contains(year))
                notes.add("现有资料不足以核对" + subject + year + "年销量合计，仅能依据已引用期间的数据回答。");
        return notes;
    }

    private String extractExcerpt(String content, String question, int maxLength)
    {
        if (requiresNamedSalesEvidence(question))
        {
            List<String> facts = extractVehicleSalesFacts(content, content, question);
            if (facts.isEmpty()) return "";
            String best = facts.get(0);
            return best.length() <= maxLength ? best : best.substring(0, maxLength).trim();
        }
        return firstLineExcerpt(content, maxLength);
    }

    private List<String> extractVehicleSalesFacts(KnowledgeChunk chunk, String question)
    {
        if (chunk == null) return List.of();
        String content = chunk.getContent();
        String brandHaystack = safe(content) + " " + safe(chunk.getSourceName()) + " "
            + safe(chunk.getOriginalName()) + " " + safe(chunk.getTitlePath());
        return extractVehicleSalesFacts(content, brandHaystack, question);
    }

    private List<String> extractVehicleSalesFacts(String content, String brandHaystack, String question)
    {
        if (content == null || content.isBlank()) return List.of();
        List<String> entities = KnowledgeTextProcessor.detectEntityTerms(question);
        if (entities.isEmpty()) return List.of();
        String brandScope = brandHaystack == null || brandHaystack.isBlank() ? content : brandHaystack;
        List<ScoredFact> scored = new ArrayList<>();
        for (String prose : content.replace("\r", "").split("[\\n。！？!?]"))
        {
            String line = prose.replaceAll("\\s+", " ").trim();
            if (line.startsWith("正文：") || line.startsWith("正文:")
                || line.startsWith("政策正文：") || line.startsWith("政策正文:"))
                line = line.substring(line.indexOf('：') >= 0 ? line.indexOf('：') + 1 : line.indexOf(':') + 1).trim();
            if (hasUsableMarketSalesJson(line) || isProvenanceOnlyMarketJson(line) || looksLikeJsonNoise(line))
                continue;
            int score = scoreSalesSentence(line, entities, question);
            if (score > 0)
            {
                String fact = line.endsWith("。") ? line : line + "。";
                scored.add(new ScoredFact(score, fact));
            }
        }
        Matcher ranking = MARKET_RANK_PATTERN.matcher(content);
        while (ranking.find())
        {
            String name = ranking.group(1);
            String amount = ranking.group(2);
            if (!entities.stream().anyMatch(term -> name.toLowerCase().contains(term.toLowerCase()))) continue;
            String period = nearestPeriodLabel(content, ranking.start());
            String fact = (period.isBlank() ? "" : period)
                + name + "销量为" + formatVehicleUnits(amount) + "。";
            scored.add(new ScoredFact(scoreSalesSentence(fact, entities, question) + 4, fact));
        }
        appendMonthlyYearTotals(scored, content, brandScope, question, entities);
        scored.sort((a, b) -> Integer.compare(b.score(), a.score()));
        List<String> facts = new ArrayList<>();
        for (ScoredFact item : scored)
        {
            if (item.score() <= 0) continue;
            boolean duplicate = facts.stream().anyMatch(existing -> normalizeFactKey(existing).equals(normalizeFactKey(item.line())));
            if (duplicate) continue;
            facts.add(item.line());
            if (facts.size() >= 4) break;
        }
        return facts;
    }

    /** 周报 fact pack / 折线 JSON 里的月度序列可以按提问年份相加，不能只拿最新单月排名当全年。 */
    private void appendMonthlyYearTotals(List<ScoredFact> scored, String content, String brandHaystack,
        String question, List<String> entities)
    {
        if (!entities.stream().anyMatch(term -> brandHaystack.toLowerCase().contains(term.toLowerCase()))) return;
        Map<String, Double> byMonth = new LinkedHashMap<>();
        Matcher brandedSeries = Pattern.compile(
            "(?is)\"(?:name|对象|title)\"\\s*:\\s*\"([^\"]{1,80})\"\\s*,\\s*\"points\"\\s*:\\s*\\[(.*?)]")
            .matcher(content);
        boolean matchedSeries = false;
        while (brandedSeries.find())
        {
            String name = brandedSeries.group(1);
            if (!entities.stream().anyMatch(term -> name.toLowerCase().contains(term.toLowerCase()))) continue;
            matchedSeries = true;
            Matcher points = TREND_POINT_PATTERN.matcher(brandedSeries.group(2));
            while (points.find())
                byMonth.put(points.group(1) + "-" + points.group(2), Double.parseDouble(points.group(3)));
        }
        if (!matchedSeries)
        {
            Matcher points = TREND_POINT_PATTERN.matcher(content);
            while (points.find())
                byMonth.put(points.group(1) + "-" + points.group(2), Double.parseDouble(points.group(3)));
        }
        if (byMonth.size() < 2) return;
        Map<String, double[]> byYear = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : byMonth.entrySet())
        {
            String year = entry.getKey().substring(0, 4);
            double[] bucket = byYear.computeIfAbsent(year, ignored -> new double[2]);
            bucket[0] += entry.getValue();
            bucket[1] += 1;
        }
        String subject = entities.stream()
            .filter(term -> brandHaystack.toLowerCase().contains(term.toLowerCase()))
            .findFirst().orElse(entities.get(0));
        String metric = content.contains("批发") ? "批发销量" : "销量";
        List<String> asked = yearsIn(question);
        List<String> years = asked.isEmpty() ? new ArrayList<>(byYear.keySet()) : asked;
        for (String year : years)
        {
            double[] bucket = byYear.get(year);
            if (bucket == null || bucket[1] < 2) continue;
            int months = (int) bucket[1];
            String scope = months >= 12 ? year + "年1-12月" : year + "年已有" + months + "个月";
            String fact = subject + scope + "月度" + metric + "合计为" + formatVehicleUnits(String.valueOf(bucket[0]))
                + "，由整车市场周报月度数据相加，不是另行发布的年报口径。";
            scored.add(new ScoredFact(scoreSalesSentence(fact, entities, question) + 30, fact));
        }
    }

    private String nearestPeriodLabel(String content, int around)
    {
        int from = Math.max(0, around - 240);
        int to = Math.min(content.length(), around + 80);
        String window = content.substring(from, to);
        Matcher month = Pattern.compile("(20\\d{2}\\s*年\\s*\\d{1,2}\\s*月)").matcher(window);
        String label = "";
        while (month.find()) label = month.group(1).replaceAll("\\s+", "");
        if (!label.isBlank()) return label;
        Matcher year = YEAR_PATTERN.matcher(window);
        while (year.find()) label = year.group(1) + "年";
        return label;
    }

    private String formatVehicleUnits(String amount)
    {
        try
        {
            double numeric = Double.parseDouble(amount);
            String formatted = Math.rint(numeric) == numeric
                ? String.format("%,.0f", numeric) : String.format("%,.2f", numeric);
            return formatted + "辆";
        }
        catch (Exception ignored)
        {
            return amount + "辆";
        }
    }

    private boolean looksLikeJsonNoise(String text)
    {
        if (text == null || text.isBlank()) return false;
        if (hasUsableMarketSalesJson(text)) return false;
        String value = text.trim();
        if (isProvenanceOnlyMarketJson(value)) return true;
        long quotes = value.chars().filter(ch -> ch == '"').count();
        return quotes >= 6 && value.contains("\":");
    }

    /** 周报里可直接抽取销量结论的结构化 JSON（含月度序列、OEM 排名）。 */
    private boolean hasUsableMarketSalesJson(String text)
    {
        if (text == null || text.isBlank()) return false;
        return text.contains("monthly_trend")
            || text.contains("\"销量/数值\"")
            || (text.contains("\"时间\":\"20") && text.contains("\"数值\""))
            || (text.contains("\"points\"") && text.contains("\"时间\""));
    }

    /** 只有 source_file / source_sheet 这类溯源字段、没有销量序列的碎片。 */
    private boolean isProvenanceOnlyMarketJson(String text)
    {
        if (text == null || text.isBlank()) return false;
        if (hasUsableMarketSalesJson(text)) return false;
        return text.contains("\"source_file\"") || text.contains("\"source_sheet\"")
            || text.contains("\"source_row\"") || text.contains("\"source_column\"");
    }

    private int scoreSalesSentence(String line, List<String> entities, String question)
    {
        if (line == null || line.isBlank() || entities == null || entities.isEmpty()) return 0;
        if (hasUsableMarketSalesJson(line) || looksLikeJsonNoise(line) || isProvenanceOnlyMarketJson(line)) return 0;
        String lower = line.toLowerCase();
        boolean brand = entities.stream().anyMatch(term -> lower.contains(term.toLowerCase()));
        if (!brand) return 0;
        boolean sales = lower.contains("销量") || lower.contains("销售") || lower.contains("万辆")
            || lower.matches(".*\\d[\\d,]*\\s*辆.*");
        if (!sales) return 0;
        if (lower.matches("(?s).*(hud|adayo|千片|kpcs|出货|闪充|充电|poc|功能测试).*")) return 0;
        List<String> askedYears = yearsIn(question);
        if (!askedYears.isEmpty() && askedYears.stream().noneMatch(lower::contains)) return 0;
        int score = 10;
        for (String year : askedYears)
            if (lower.contains(year)) score += 6;
        if (lower.contains("年") && lower.contains("月")) score += 2;
        return score;
    }

    private List<String> yearsIn(String question)
    {
        List<String> years = new ArrayList<>();
        if (question == null) return years;
        Matcher matcher = YEAR_PATTERN.matcher(question);
        while (matcher.find())
        {
            String year = matcher.group(1);
            if (!years.contains(year)) years.add(year);
        }
        return years;
    }

    private String normalizeFactKey(String value)
    {
        return value == null ? "" : value.replaceAll("\\s+", "")
            .replaceAll("\\[S\\d+\\]", "")
            .replace(",", "")
            .toLowerCase();
    }

    private String safe(String value) { return value == null ? "" : value; }

    private record ScoredFact(int score, String line) { }

    private String firstLineExcerpt(String content, int maxLength)
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
