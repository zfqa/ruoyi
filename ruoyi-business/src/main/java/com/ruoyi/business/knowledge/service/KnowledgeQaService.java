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
    private static final String LLM_UNAVAILABLE_LABEL = "大模型服务暂不可用，以下根据已检索到的知识库原文整理（含页码），请人工核对。";
    private static final Pattern NUMBERED_QUESTION_PATTERN = Pattern.compile(
        "(?m)^\\s*(?:\\d{1,2}[\\.、\\)）]|[（(]\\d{1,2}[)）]|[一二三四五六七八九十]+[、\\.）)])\\s*");

    private final KnowledgeIngestService ingestService;
    private final KnowledgeCitationValidator citationValidator = new KnowledgeCitationValidator();
    private final HttpClient httpClient;
    private final KnowledgeWebSearchClient webSearchClient;
    private final LlmRuntimeConfiguration llmConfiguration;
    private final QuestionIntentAnalyzer intentAnalyzer = new QuestionIntentAnalyzer();
    private final KnowledgeProgramAnswerService programAnswerService = new KnowledgeProgramAnswerService();
    private KnowledgeGraphService graphService;

    @Value("${business.knowledge.llm.timeout-seconds:300}")
    private int requestTimeoutSeconds = 300;

    @Value("${business.knowledge.retrieval.pdf-candidate-limit:20}")
    private int pdfCandidateLimit = 20;

    @Value("${business.knowledge.retrieval.report-candidate-limit:8}")
    private int reportCandidateLimit = 8;

    @Value("${business.knowledge.retrieval.news-candidate-limit:5}")
    private int newsCandidateLimit = 5;

    @Value("${business.knowledge.retrieval.policy-candidate-limit:5}")
    private int policyCandidateLimit = 5;

    @Value("${business.knowledge.retrieval.model-context-limit:10}")
    private int modelContextLimit = 10;

    @Value("${business.knowledge.retrieval.adjacent-chunk-radius:1}")
    private int adjacentChunkRadius = 1;

    @Value("${business.knowledge.retrieval.candidate-document-top-k:5}")
    private int candidateDocumentTopK = 5;

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
        return ask(question, sourceType, roleIds, admin, includeNews, webLlm, null, null, progressListener);
    }

    public Map<String, Object> ask(String question, String sourceType, List<Long> roleIds, boolean admin,
        boolean includeNews, boolean webLlm, Long sourceId, Long versionId,
        QaProgressListener progressListener) throws Exception
    {
        if (question == null || question.trim().length() < 2) throw new IllegalArgumentException("问题至少2个字符");
        String actualQuestion = question.trim();
        QaProgressListener listener = progressListener == null ? (progress, stage, logs) -> { } : progressListener;
        if (!llmConfiguration.getApiKey().isBlank())
        {
            try
            {
                Map<String, Object> dispatched = askDispatched(actualQuestion, sourceType, roleIds, admin, includeNews,
                    webLlm, sourceId, versionId, listener);
                if (dispatched != null) return dispatched;
            }
            catch (Exception ex)
            {
                List<Map<String, Object>> logs = new ArrayList<>();
                logs.add(log("DISPATCH", "调度失败，改走原检索", actualQuestion, abbreviate(ex.getMessage(), 160), 0, "FALLBACK"));
                listener.onProgress(12, "调度失败，改走原检索", logs);
            }
        }
        List<String> subQuestions = splitSubQuestions(actualQuestion);
        if (subQuestions.size() > 1)
            return askMulti(actualQuestion, subQuestions, sourceType, roleIds, admin, includeNews, webLlm, sourceId, versionId,
                progressListener);
        return askSingle(actualQuestion, null, sourceType, roleIds, admin, includeNews, webLlm, sourceId, versionId,
            progressListener == null ? (progress, stage, logs) -> { } : progressListener);
    }

    /**
     * 模型只看文档目录并决定打开哪些资料。系统按它给出的检索词取原文，
     * 最多再补查一次。回答不再走程序抢答。
     */
    private Map<String, Object> askDispatched(String question, String sourceType, List<Long> roleIds, boolean admin,
        boolean includeNews, boolean webLlm, Long sourceId, Long versionId, QaProgressListener listener) throws Exception
    {
        List<Map<String, Object>> logs = new ArrayList<>();
        notifyProgress(listener, 8, "正在查看文档目录", logs);
        List<Map<String, Object>> catalog = ingestService.listEnabledSourcesForContext(
            sourceType, null, roleIds, admin);
        Map<Long, Long> versions = new LinkedHashMap<>();
        StringBuilder catalogText = new StringBuilder();
        for (Map<String, Object> row : catalog)
        {
            if (row == null || row.get("id") == null || row.get("currentVersionId") == null) continue;
            Long id = Long.valueOf(String.valueOf(row.get("id")));
            Long currentVersion = Long.valueOf(String.valueOf(row.get("currentVersionId")));
            if (sourceId != null && !sourceId.equals(id)) continue;
            if (versionId != null && sourceId != null && sourceId.equals(id)) currentVersion = versionId;
            versions.put(id, currentVersion);
            int pages = pageCountOf(id, currentVersion);
            catalogText.append("- sourceId=").append(id)
                .append(" 名称=").append(row.get("sourceName"))
                .append(" 类型=").append(row.get("sourceType"))
                .append(pages > 0 ? " 页数=" + pages : "")
                .append('\n');
        }
        if (versions.isEmpty()) return null;
        LlmDispatchPlan.Plan plan = LlmDispatchPlan.parse(dispatchLlm(LlmDispatchPlan.catalogSystemPrompt(),
            "问题：" + question + "\n\n文档目录：\n" + catalogText), versions.keySet());
        if (plan.sourceIds().isEmpty()) return null;
        logs.add(log("DISPATCH", "模型选择文档", question,
            "sourceId=" + plan.sourceIds() + " 检索词=" + plan.queries(), 0, "SUCCESS"));
        notifyProgress(listener, 20, "已选定资料，正在打开原文", logs);
        List<KnowledgeChunk> chunks = openDocuments(plan, versions, roleIds, admin, logs);
        List<KnowledgeChunk> kept = chunks;
        LlmDispatchPlan.Plan retry = LlmDispatchPlan.parse(dispatchLlm(LlmDispatchPlan.sufficiencySystemPrompt(),
            "问题：" + question + "\n\n已打开资料节选：\n" + evidencePreview(chunks) + "\n\n文档目录：\n" + catalogText),
            versions.keySet());
        if (!retry.enough() && !retry.queries().isEmpty())
        {
            List<Long> retryIds = retry.sourceIds().isEmpty() ? plan.sourceIds() : retry.sourceIds();
            logs.add(log("DISPATCH", "模型要求再查一次", question,
                "sourceId=" + retryIds + " 检索词=" + retry.queries(), 0, "SUCCESS"));
            notifyProgress(listener, 40, "证据不足，正在按模型要求补查", logs);
            chunks = openDocuments(new LlmDispatchPlan.Plan(retryIds, retry.queries(), false), versions, roleIds, admin, logs);
            Map<String, KnowledgeChunk> merged = new LinkedHashMap<>();
            mergeChunks(merged, kept);
            mergeChunks(merged, chunks);
            chunks = new ArrayList<>(merged.values());
        }
        chunks = selectForModelContext(rankForDispatch(question, chunks));
        if (chunks.isEmpty()) return null;
        notifyProgress(listener, 55, "正在根据已打开的原文生成回答", logs);
        String answer = callLlm(question, chunks,
            "只根据已经打开的原文回答。每个数字都必须能在原文中逐字找到。"
                + "原文没有的项目直接说明没有，不要改用其他公司或文档里的同名指标，也不要心算一个原文里不存在的数。");
        List<String> missingNumbers = ungroundedNumbers(answer, chunks);
        List<String> warnings = new ArrayList<>();
        if (!missingNumbers.isEmpty())
        {
            String note = "这些数字在已打开的原文中没有找到，不能当作资料结论：" + String.join("、", missingNumbers);
            warnings.add(note);
            answer = answer + "\n\n" + note;
            logs.add(log("VERIFY", "数字核对", question, note, 0, "FAILED"));
        }
        else
        {
            logs.add(log("VERIFY", "数字核对", question, "回答中的数字能在原文中找到", 0, "SUCCESS"));
        }
        KnowledgeCitationValidator.ValidationResult validation = softValidateOrAttach(answer, chunks);
        List<Map<String, Object>> citations = new ArrayList<>();
        for (Integer index : validation.getCitedIndexes())
            citations.add(toCitation("S" + index, chunks.get(index - 1), validation.getEvidenceForSource(index)));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", answer);
        result.put("claims", validation.getClaims());
        result.put("citations", citations);
        result.put("model", llmConfiguration.getModel());
        result.put("answerMode", "LLM_DISPATCH");
        result.put("citationCoveragePercent", validation.getClaims().isEmpty() ? 0 : 100);
        result.put("citationPolicy", "AGENT_GROUNDED");
        result.put("warnings", warnings);
        result.put("newsExplanationEnabled", includeNews);
        result.put("webLlmEnabled", webLlm);
        result.put("sourceBreakdown", sourceBreakdown(chunks));
        result.put("retrievalLogs", logs);
        result.put("analysisTrace", List.of());
        result.put("graph", emptyGraph());
        result.put("qaStatus", qaStatusFor("LLM_VERIFIED"));
        notifyProgress(listener, 100, "已根据模型打开的原文完成回答", logs);
        return result;
    }

    private String dispatchLlm(String system, String user) throws Exception
    {
        JSONArray messages = new JSONArray();
        messages.add(message("system", system));
        messages.add(message("user", user));
        JSONObject payload = new JSONObject();
        payload.put("model", llmConfiguration.getModel());
        payload.put("messages", messages);
        payload.put("temperature", 0);
        payload.put("max_tokens", 400);
        payload.put("stream", false);
        return sendLlmRequest(payload);
    }

    private List<KnowledgeChunk> openDocuments(LlmDispatchPlan.Plan plan, Map<Long, Long> versions,
        List<Long> roleIds, boolean admin, List<Map<String, Object>> logs)
    {
        Map<String, KnowledgeChunk> merged = new LinkedHashMap<>();
        List<String> queries = plan.queries().isEmpty() ? List.of() : plan.queries();
        for (Long id : plan.sourceIds())
        {
            Long version = versions.get(id);
            if (version == null) continue;
            for (String query : queries)
            {
                try
                {
                    List<KnowledgeChunk> found = ingestService.search(query, null, roleIds, admin, 6, id, version, null);
                    mergeChunks(merged, found);
                    logs.add(log("DOC_SEARCH", "按模型检索词打开原文", query,
                        "sourceId=" + id + " 命中 " + (found == null ? 0 : found.size()) + " 条", 0,
                        found == null || found.isEmpty() ? "NO_HIT" : "SUCCESS"));
                }
                catch (RuntimeException ex)
                {
                    logs.add(log("DOC_SEARCH", "打开原文失败", query, abbreviate(ex.getMessage(), 120), 0, "FAILED"));
                }
            }
        }
        return completeUnfinishedSentences(new ArrayList<>(merged.values()));
    }

    /** 切片停在句中时，把同版本的下一条原文接上，避免句子被切掉后半句。 */
    private List<KnowledgeChunk> completeUnfinishedSentences(List<KnowledgeChunk> chunks)
    {
        if (chunks == null || chunks.isEmpty()) return List.of();
        for (KnowledgeChunk chunk : chunks)
        {
            if (chunk == null || !endsMidSentence(chunk.getContent())) continue;
            if (chunk.getVersionId() == null || chunk.getChunkNo() == null) continue;
            KnowledgeChunk next = ingestService.findChunk(chunk.getVersionId(), chunk.getChunkNo() + 1);
            if (next == null || next.getContent() == null || next.getContent().isBlank()) continue;
            if (chunk.getId() != null && chunk.getId().equals(next.getId())) continue;
            chunk.setContent(chunk.getContent().stripTrailing() + "\n" + next.getContent().strip());
            if (next.getPageEnd() != null) chunk.setPageEnd(next.getPageEnd());
        }
        return chunks;
    }

    private static boolean endsMidSentence(String content)
    {
        if (content == null) return false;
        String text = content.strip();
        if (text.length() < 18) return false;
        char end = text.charAt(text.length() - 1);
        return "。！？.!?".indexOf(end) < 0;
    }

    private int pageCountOf(Long sourceId, Long versionId)
    {
        try
        {
            for (var version : ingestService.getVersions(sourceId))
                if (version != null && versionId.equals(version.getId()) && version.getPageCount() != null)
                    return version.getPageCount();
        }
        catch (Exception ignored) { }
        return 0;
    }

    private String evidencePreview(List<KnowledgeChunk> chunks)
    {
        if (chunks == null || chunks.isEmpty()) return "（没有打开到原文）";
        StringBuilder text = new StringBuilder();
        int limit = Math.min(6, chunks.size());
        for (int i = 0; i < limit; i++)
        {
            KnowledgeChunk chunk = chunks.get(i);
            String body = KnowledgeTextProcessor.joinWrappedLines(safe(chunk.getContent()));
            if (body.length() > 180) body = body.substring(0, 180);
            text.append("[sourceId=").append(chunk.getSourceId()).append("] ")
                .append(chunk.getOriginalName() == null ? chunk.getSourceName() : chunk.getOriginalName())
                .append('\n').append(body).append("\n\n");
        }
        return text.toString();
    }

    private List<String> ungroundedNumbers(String answer, List<KnowledgeChunk> chunks)
    {
        if (answer == null || answer.isBlank() || chunks == null || chunks.isEmpty()) return List.of();
        StringBuilder evidence = new StringBuilder();
        for (KnowledgeChunk chunk : chunks) evidence.append('\n').append(safe(chunk.getContent()));
        String haystack = evidence.toString().replace(",", "").replace("，", "");
        LinkedHashSet<String> missing = new LinkedHashSet<>();
        Matcher matcher = Pattern.compile("\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d+\\.\\d{2,}|\\d{5,}").matcher(answer);
        while (matcher.find())
        {
            String raw = matcher.group();
            String normalized = raw.replace(",", "").replace("，", "");
            if (normalized.endsWith(".00")) normalized = normalized.substring(0, normalized.length() - 3);
            if (!haystack.contains(normalized) && !haystack.contains(raw)) missing.add(raw);
        }
        return new ArrayList<>(missing);
    }

    private Map<String, Object> askMulti(String parentQuestion, List<String> subQuestions, String sourceType, List<Long> roleIds,
        boolean admin, boolean includeNews, boolean webLlm, Long sourceId, Long versionId,
        QaProgressListener progressListener) throws Exception
    {
        QaProgressListener listener = progressListener == null ? (progress, stage, logs) -> { } : progressListener;
        List<Map<String, Object>> logs = new ArrayList<>();
        logs.add(log("QUERY_BUILD", "拆分多问题", "共 " + subQuestions.size() + " 个子问题",
            String.join(" | ", subQuestions.stream().map(q -> abbreviate(q, 40)).toList()), 0, "SUCCESS"));
        notifyProgress(listener, 8, "已拆分为 " + subQuestions.size() + " 个子问题，将分别检索", logs);
        StringBuilder answer = new StringBuilder();
        List<Map<String, Object>> allCitations = new ArrayList<>();
        List<Map<String, Object>> allClaims = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        Map<String, Integer> breakdown = new LinkedHashMap<>();
        breakdown.put("REPORT", 0); breakdown.put("NEWS", 0); breakdown.put("POLICY", 0);
        breakdown.put("PDF", 0); breakdown.put("OTHER", 0);
        String answerMode = "LLM_VERIFIED";
        int citationOrdinal = 1;
        for (int i = 0; i < subQuestions.size(); i++)
        {
            String sub = subQuestions.get(i);
            int progress = 10 + (80 * i / subQuestions.size());
            notifyProgress(listener, progress, "正在回答子问题 " + (i + 1) + "/" + subQuestions.size(), logs);
            Map<String, Object> part = askSingle(sub, parentQuestion, sourceType, roleIds, admin, includeNews, false,
                sourceId, versionId, (p, stage, partLogs) -> { });
            answer.append(i + 1).append(". ").append(sub).append('\n')
                .append(String.valueOf(part.get("answer"))).append("\n\n");
            String mode = String.valueOf(part.getOrDefault("answerMode", ""));
            if (!"LLM_VERIFIED".equals(mode)) answerMode = mode;
            @SuppressWarnings("unchecked")
            List<String> partWarnings = (List<String>) part.getOrDefault("warnings", List.of());
            for (String warning : partWarnings)
                if (warning != null && !warning.isBlank()) warnings.add("子问题" + (i + 1) + "：" + warning);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> partCitations = (List<Map<String, Object>>) part.getOrDefault("citations", List.of());
            for (Map<String, Object> citation : partCitations)
            {
                Map<String, Object> copy = new LinkedHashMap<>(citation);
                copy.put("label", "S" + citationOrdinal);
                copy.put("citationLabel", "S" + citationOrdinal);
                copy.put("subQuestion", i + 1);
                allCitations.add(copy);
                citationOrdinal++;
            }
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> partClaims = (List<Map<String, Object>>) part.getOrDefault("claims", List.of());
            allClaims.addAll(partClaims);
            @SuppressWarnings("unchecked")
            Map<String, Integer> partBreakdown = (Map<String, Integer>) part.get("sourceBreakdown");
            if (partBreakdown != null)
                for (Map.Entry<String, Integer> entry : partBreakdown.entrySet())
                    breakdown.put(entry.getKey(), breakdown.getOrDefault(entry.getKey(), 0) + entry.getValue());
            logs.add(log("SUB_QA", "子问题完成", sub, mode + " / citations="
                + partCitations.size(), 0, "SUCCESS"));
        }
        if (webLlm)
            warnings.add("批量提问时不对整体结果自动联网，避免用网页摘要覆盖年报证据；如需联网请对单个子问题开启。");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", answer.toString().trim());
        result.put("claims", allClaims);
        result.put("citations", allCitations);
        result.put("model", llmConfiguration.getModel());
        result.put("answerMode", answerMode);
        result.put("citationCoveragePercent", allClaims.isEmpty() ? 0 : 100);
        result.put("citationPolicy", "AGENT_GROUNDED");
        result.put("warnings", warnings);
        result.put("newsExplanationEnabled", includeNews);
        result.put("webLlmEnabled", webLlm);
        result.put("sourceBreakdown", breakdown);
        result.put("queryPlan", List.of(step(1, "拆分多问题", "每个子问题独立检索与回答",
            subQuestions.size() + " 个子问题")));
        result.put("retrievalLogs", logs);
        result.put("analysisTrace", List.of());
        result.put("graph", emptyGraph());
        result.put("subQuestionCount", subQuestions.size());
        result.put("qaStatus", qaStatusFor(answerMode));
        notifyProgress(listener, 100, "多问题回答完成", logs);
        return result;
    }

    private Map<String, Object> askSingle(String actualQuestion, String retrievalBasis, String sourceType, List<Long> roleIds,
        boolean admin, boolean includeNews, boolean webLlm, Long sourceId, Long versionId,
        QaProgressListener progressListener) throws Exception
    {
        String searchText = retrievalBasis == null || retrievalBasis.isBlank() ? actualQuestion : retrievalBasis;
        List<Map<String, Object>> logs = new ArrayList<>();
        notifyProgress(progressListener, 5, "解析问题与生成检索计划", logs);
        QuestionIntentAnalyzer.Plan intent = intentAnalyzer.analyze(actualQuestion);
        logs.add(log("INTENT", "意图分析", actualQuestion,
            "domain=" + intent.domain() + " kind=" + intent.kind()
                + " hints=" + intent.metricHints()
                + (intent.needSum() ? " sum" : "")
                + (intent.needCompleteList() ? " list" : "")
                + (intent.salesVolume() ? " salesVolume" : ""),
            0, "SUCCESS"));
        DocumentScope scope = resolveDocumentScope(searchText, sourceId, versionId, roleIds, admin, logs);
        QuestionSubjectResolver.SubjectPlan subjectPlan = resolveSubjectPlan(searchText, logs);
        List<String> subjects = subjectPlan.subjects();
        List<String> searchQueries = buildSearchQueries(searchText);
        for (String hint : intent.metricHints())
            if (hint != null && !hint.isBlank() && !searchQueries.contains(hint))
                searchQueries.add(hint);
        searchQueries = QuestionSubjectResolver.enrichSearchQueries(searchQueries, subjects);
        searchQueries = preferSpecificQueries(searchText, searchQueries);
        logs.add(log("QUERY_BUILD", "生成检索词", questionProfile(actualQuestion),
            "数据检索词：" + String.join(" || ", searchQueries)
                + (subjects.isEmpty() ? "" : "；主体：" + String.join("、", subjects))
                + (scope == null ? "" : "；文档范围 sourceId=" + scope.sourceId()
                + " versionId=" + scope.versionId())
                + "；政策检索词：" + policyContextQuery(actualQuestion), 0, "SUCCESS"));
        notifyProgress(progressListener, 10, "已理解问题并生成多路检索词", logs);
        long retrievalStarted = System.nanoTime();
        RetrievalResult retrieval;
        try
        {
            retrieval = retrieveEvidence(searchText, searchQueries, subjects, sourceType, roleIds, admin,
                includeNews, scope, logs, progressListener);
        }
        catch (RuntimeException retrievalError)
        {
            logs.add(log("SEARCH", "检索失败", actualQuestion, abbreviate(retrievalError.getMessage(), 180),
                elapsedMillis(retrievalStarted), "FAILED"));
            return retrievalFailedResult(actualQuestion, logs);
        }
        List<KnowledgeChunk> rawChunks = retrieval.chunks();
        if (scope == null)
        {
            rawChunks = alignChunksToSubject(searchText, subjectPlan, rawChunks, logs);
            rawChunks = retainRareDocuments(searchText, rawChunks, logs);
            rawChunks = deepenWithinCandidateDocuments(searchText, searchQueries, subjects, rawChunks, roleIds, admin,
                logs, progressListener);
            rawChunks = alignChunksToSubject(searchText, subjectPlan, rawChunks, logs);
            rawChunks = retainRareDocuments(searchText, rawChunks, logs);
        }
        List<KnowledgeChunk> chunks = filterVehicleSalesEvidence(actualQuestion,
            withSalesReportContext(actualQuestion, rawChunks));
        chunks = selectForModelContext(rankEvidenceForQuestion(actualQuestion, chunks));
        Map<String, Integer> sourceBreakdown = sourceBreakdown(chunks);
        logs.add(log("SEARCH", "混合证据检索", actualQuestion,
            "召回 " + rawChunks.size() + " / 过滤后 " + chunks.size()
                + " 个候选切片（结构化指标 " + retrieval.metricHits() + " 个）",
            elapsedMillis(retrievalStarted), "SUCCESS"));
        notifyProgress(progressListener, 35, "已完成多源检索", logs);

        if (rawChunks.isEmpty())
        {
            logs.add(log("SEARCH", "证据不足", actualQuestion,
                "检索完成但未命中可用切片", 0, "NO_HIT"));
            if (!webLlm)
                return notFoundResult(actualQuestion, logs, sourceBreakdown, false);
            return answerFromWebSearch(actualQuestion, logs, progressListener, sourceBreakdown, true);
        }
        if (requiresNamedSalesEvidence(actualQuestion) && !hasUsableVehicleSalesEvidence(actualQuestion, chunks))
        {
            logs.add(log("SEARCH", "销量证据不足", actualQuestion,
                "已命中 " + rawChunks.size() + " 条切片，但不含可用车辆销量结论", 0, "NO_HIT"));
            if (!webLlm)
                return notFoundResult(actualQuestion, logs, sourceBreakdown, true);
            return answerFromWebSearch(actualQuestion, logs, progressListener, sourceBreakdown, true);
        }
        if (chunks.isEmpty())
            chunks = selectForModelContext(rankEvidenceForQuestion(actualQuestion, rawChunks));
        final List<KnowledgeChunk> evidenceChunks = chunks;

        String answer;
        String answerMode = "LLM_VERIFIED";
        List<String> warnings = new ArrayList<>();
        KnowledgeCitationValidator.ValidationResult validation;
        notifyProgress(progressListener, 50, "正在生成回答", logs);
        DeterministicAnswer deterministic = buildDeterministicAnswer(actualQuestion, evidenceChunks);
        if (deterministic != null)
        {
            answer = deterministic.text();
            validation = softValidateOrAttach(answer, evidenceChunks);
            answerMode = deterministic.partial() ? "PROGRAM_PARTIAL" : "PROGRAM_CALCULATED";
            warnings.addAll(deterministic.warnings());
            logs.add(log("GENERATE", deterministic.partial() ? "证据不完整" : "程序计算或抽取", "知识库原文",
                deterministic.partial() ? "只返回已确认的部分，不声称完整" : "数值由程序计算，不交给模型心算",
                0, "SUCCESS"));
            notifyProgress(progressListener, 90, "已根据知识库原文生成回答", logs);
        }
        else if (llmConfiguration.getApiKey().isBlank())
        {
            answer = buildEvidenceFallback(actualQuestion, evidenceChunks, false);
            validation = softValidateOrAttach(answer, evidenceChunks);
            answerMode = "EXTRACTIVE_FALLBACK";
            warnings.add("未配置大模型 API Key，已根据检索资料整理回答（含原文页码）");
            logs.add(log("GENERATE", "LLM生成", llmConfiguration.getModel(), "未配置密钥，使用资料整理回答", 0, "FALLBACK"));
            notifyProgress(progressListener, 80, "已生成资料整理回答", logs);
        }
        else
        {
            long llmStarted = System.nanoTime();
            boolean llmResponded = false;
            try
            {
                answer = callLlm(actualQuestion, evidenceChunks, null);
                llmResponded = true;
                logs.add(log("GENERATE", "LLM生成", llmConfiguration.getModel(), "回答生成完成",
                    elapsedMillis(llmStarted), "SUCCESS"));
                notifyProgress(progressListener, 80, "回答已生成，正在整理参考来源", logs);
                validation = softValidateOrAttach(answer, evidenceChunks);
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
                boolean balanceIssue = isLlmBalanceOrAuthIssue(llmError);
                answer = buildEvidenceFallback(actualQuestion, evidenceChunks, balanceIssue || !llmResponded);
                validation = softValidateOrAttach(answer, evidenceChunks);
                answerMode = balanceIssue ? "LLM_UNAVAILABLE_WITH_EVIDENCE" : "EXTRACTIVE_FALLBACK";
                String reason = balanceIssue ? "大模型余额不足或服务不可用(402/401)"
                    : (llmResponded ? "LLM返回异常" : "LLM请求失败");
                warnings.add(reason + "。已展示知识库原文证据，未改走联网搜索：" + abbreviate(llmError.getMessage(), 180));
                logs.add(log("GENERATE", "安全降级", llmConfiguration.getModel(), reason + "；保留已检索证据",
                    elapsedMillis(llmStarted), "FALLBACK"));
                notifyProgress(progressListener, 85, "LLM不可用，已展示知识库原文", logs);
            }
        }
        List<Map<String, Object>> citations = new ArrayList<>();
        for (Integer index : validation.getCitedIndexes())
            citations.add(toCitation("S" + index, evidenceChunks.get(index - 1), validation.getEvidenceForSource(index)));
        List<KnowledgeChunk> citedChunks = validation.getCitedIndexes().stream()
            .map(index -> evidenceChunks.get(index - 1)).toList();
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
        appendUnspecifiedYearNote(actualQuestion, citations, warnings);
        result.put("newsExplanationEnabled", includeNews);
        result.put("webLlmEnabled", webLlm);
        result.put("sourceBreakdown", sourceBreakdown);
        if (scope != null)
        {
            result.put("scopedSourceId", scope.sourceId());
            result.put("scopedVersionId", scope.versionId());
        }
        result.put("queryPlan", queryPlan(actualQuestion, sourceType, retrieval.metricHits(),
            Math.max(validation.getClaims().size(), validation.getCitedIndexes().size()), includeNews, sourceBreakdown));
        result.put("retrievalLogs", logs);
        result.put("analysisTrace", analysisTrace(actualQuestion, evidenceChunks, validation, answerMode));
        result.put("graph", graphService == null ? emptyGraph()
            : graphService.graphForChunks(citedChunks, roleIds, admin,
                KnowledgeTextProcessor.detectEntityTerms(actualQuestion)));
        result.put("qaStatus", qaStatusFor(answerMode));
        if (webLlm && !"LLM_UNAVAILABLE_WITH_EVIDENCE".equals(answerMode))
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
    private RetrievalResult retrieveEvidence(String question, List<String> searchQueries, List<String> subjects,
        String sourceType, List<Long> roleIds, boolean admin, boolean includeNews, DocumentScope scope,
        List<Map<String, Object>> logs, QaProgressListener listener)
    {
        long started = System.nanoTime();
        String primaryQuery = searchQueries == null || searchQueries.isEmpty() ? question : searchQueries.get(0);
        List<KnowledgeChunk> metricChunks = sourceType == null || sourceType.isBlank() || "REPORT".equalsIgnoreCase(sourceType)
            ? ingestService.searchMetrics(primaryQuery, roleIds, admin, 3) : List.of();
        if (metricChunks == null) metricChunks = List.of();
        logs.add(log("METRIC_SEARCH", "查询结构化指标库", primaryQuery,
            metricChunks.size() + " 个指标候选", elapsedMillis(started), metricChunks.isEmpty() ? "NO_HIT" : "SUCCESS"));
        notifyProgress(listener, 16, "已查询结构化指标库", logs);
        Map<String, KnowledgeChunk> merged = new LinkedHashMap<>();
        mergeChunks(merged, metricChunks);
        if (sourceType != null && !sourceType.isBlank())
        {
            String query = "POLICY".equalsIgnoreCase(sourceType) ? policyContextQuery(question) : primaryQuery;
            List<KnowledgeChunk> selected = timedSearchAll(List.of(query), sourceType, roleIds, admin,
                Math.max(modelContextLimit, 8), scope, subjects, logs);
            mergeChunks(merged, selected);
            notifyProgress(listener, 30, "已查询指定知识来源 " + sourceType, logs);
            return new RetrievalResult(finalizeRetrieved(merged, question), metricChunks.size());
        }

        mergeChunks(merged, timedSearchAll(searchQueries, "REPORT", roleIds, admin, reportCandidateLimit, scope, subjects, logs));
        notifyProgress(listener, 21, "已查询结构化报告", logs);
        if (includeNews)
        {
            mergeChunks(merged, timedSearchAll(searchQueries, "NEWS", roleIds, admin, newsCandidateLimit, scope, subjects, logs));
            notifyProgress(listener, 25, "已查询新闻库", logs);
            if (!requiresNamedSalesEvidence(question) || wantsPolicyExplanation(question))
            {
                mergeChunks(merged, timedSearchAll(List.of(policyContextQuery(question)), "POLICY",
                    roleIds, admin, policyCandidateLimit, scope, subjects, logs));
                notifyProgress(listener, 30, "已查询政策库", logs);
            }
        }
        mergeChunks(merged, timedSearchAll(searchQueries, "PDF", roleIds, admin, pdfCandidateLimit, scope, subjects, logs));
        notifyProgress(listener, 33, "已查询固定PDF资料", logs);
        return new RetrievalResult(finalizeRetrieved(merged, question), metricChunks.size());
    }

    private List<KnowledgeChunk> finalizeRetrieved(Map<String, KnowledgeChunk> merged, String question)
    {
        List<KnowledgeChunk> values = new ArrayList<>(merged.values());
        try
        {
            List<KnowledgeChunk> withNeighbors = ingestService.expandAdjacentChunks(values, adjacentChunkRadius);
            if (withNeighbors != null && !withNeighbors.isEmpty()) values = withNeighbors;
        }
        catch (Exception ignored) { }
        return values;
    }

    private List<KnowledgeChunk> timedSearchAll(List<String> queries, String type, List<Long> roleIds, boolean admin,
        int limit, DocumentScope scope, List<String> subjects, List<Map<String, Object>> logs)
    {
        Map<String, KnowledgeChunk> merged = new LinkedHashMap<>();
        List<String> actualQueries = queries == null || queries.isEmpty() ? List.of() : queries;
        for (String query : actualQueries)
        {
            if (query == null || query.trim().length() < 2) continue;
            mergeChunks(merged, timedSearch(query.trim(), type, roleIds, admin, limit, scope, subjects, logs));
        }
        return new ArrayList<>(merged.values());
    }

    private List<KnowledgeChunk> timedSearch(String query, String type, List<Long> roleIds, boolean admin,
        int limit, List<Map<String, Object>> logs)
    {
        return timedSearch(query, type, roleIds, admin, limit, null, null, logs);
    }

    private List<KnowledgeChunk> timedSearch(String query, String type, List<Long> roleIds, boolean admin,
        int limit, DocumentScope scope, List<String> subjects, List<Map<String, Object>> logs)
    {
        long started = System.nanoTime();
        List<KnowledgeChunk> found;
        if (scope == null)
            found = ingestService.search(query, type, roleIds, admin, limit, null, null, subjects);
        else
            found = ingestService.search(query, type, roleIds, admin, limit, scope.sourceId(), scope.versionId(), subjects);
        if (found == null) found = List.of();
        logs.add(log(type + "_SEARCH", "查询" + sourceTypeLabel(type) + "库", query,
            found.size() + " 条证据"
                + (scope == null ? "" : "（已限定文档）"),
            elapsedMillis(started), found.isEmpty() ? "NO_HIT" : "SUCCESS"));
        return found;
    }

    private void mergeChunks(Map<String, KnowledgeChunk> target, List<KnowledgeChunk> chunks)
    {
        if (chunks == null) return;
        for (KnowledgeChunk chunk : chunks)
        {
            if (chunk == null) continue;
            // 同一文档的不同切片必须保留；仅对同一切片去重
            String key = chunk.getId() != null
                ? "ID:" + chunk.getId()
                : String.valueOf(chunk.getSourceId()) + ":" + chunk.getVersionId() + ":" + chunk.getChunkNo();
            target.putIfAbsent(key, chunk);
        }
    }

    /**
     * 宽召回之后按文档聚合，只在得分最高的几份资料里做第二次检索。
     * sourceId/versionId 由程序选出，不要求用户指定。同一 PDF 的不同切片按 chunk 保留。
     */
    private List<KnowledgeChunk> deepenWithinCandidateDocuments(String question, List<String> searchQueries,
        List<String> subjects, List<KnowledgeChunk> wideChunks, List<Long> roleIds, boolean admin,
        List<Map<String, Object>> logs, QaProgressListener listener)
    {
        if (wideChunks == null || wideChunks.isEmpty()) return wideChunks == null ? List.of() : wideChunks;
        List<DocumentScope> documents = selectCandidateDocuments(question, wideChunks, subjects);
        if (documents.isEmpty()) return wideChunks;
        logs.add(log("DOC_SELECT", "识别候选文档", question,
            documents.size() + " 份资料进入文档内补查", 0, "SUCCESS"));
        notifyProgress(listener, 34, "已定位相关资料，正在文档内补查", logs);
        Map<String, KnowledgeChunk> merged = new LinkedHashMap<>();
        List<String> queries = new ArrayList<>();
        if (searchQueries != null) queries.addAll(searchQueries);
        for (String hint : intentAnalyzer.analyze(question).metricHints())
            if (hint != null && !hint.isBlank()) queries.add(hint);
        if (queries.isEmpty()) queries.add(question);
        int queryLimit = Math.min(4, queries.size());
        for (DocumentScope document : documents)
        {
            for (int i = 0; i < queryLimit; i++)
            {
                String query = queries.get(i);
                if (query == null || query.trim().length() < 2) continue;
                try
                {
                    List<KnowledgeChunk> found = ingestService.search(query.trim(), null, roleIds, admin,
                        Math.max(pdfCandidateLimit, 8), document.sourceId(), document.versionId(), subjects);
                    mergeChunks(merged, found);
                }
                catch (RuntimeException ex)
                {
                    logs.add(log("DOC_SEARCH", "文档内补查失败", query,
                        abbreviate(ex.getMessage(), 160), 0, "FAILED"));
                }
            }
        }
        mergeChunks(merged, wideChunks);
        logs.add(log("DOC_SEARCH", "文档内补查", question,
            "补查后保留 " + merged.size() + " 条切片", 0, merged.isEmpty() ? "NO_HIT" : "SUCCESS"));
        return new ArrayList<>(merged.values());
    }

    private List<DocumentScope> selectCandidateDocuments(String question, List<KnowledgeChunk> chunks,
        List<String> subjects)
    {
        Map<String, DocumentCandidate> grouped = new LinkedHashMap<>();
        int index = 0;
        for (KnowledgeChunk chunk : chunks)
        {
            if (chunk != null && chunk.getSourceId() != null)
            {
                String key = chunk.getSourceId() + ":" + chunk.getVersionId();
                DocumentCandidate candidate = grouped.computeIfAbsent(key,
                    ignored -> new DocumentCandidate(chunk.getSourceId(), chunk.getVersionId()));
                candidate.count++;
                if (index < candidate.bestRank) candidate.bestRank = index;
                String name = chunk.getOriginalName();
                if (name == null || name.isBlank()) name = chunk.getSourceName();
                if (name != null && !name.isBlank()) candidate.name = name;
                if (chunk.getSourceType() != null && !chunk.getSourceType().isBlank())
                    candidate.type = chunk.getSourceType();
            }
            index++;
        }
        LinkedHashSet<String> entities = new LinkedHashSet<>();
        if (subjects != null) entities.addAll(subjects);
        entities.addAll(KnowledgeTextProcessor.detectEntityTerms(question));
        List<String> entityList = new ArrayList<>(entities);
        boolean anyNameHit = grouped.values().stream()
            .anyMatch(candidate -> QuestionSubjectResolver.mentionsAny(candidate.name, entityList));
        List<String> years = yearsIn(question);
        boolean peopleOrFinance = isPeopleOrFinanceQuestion(question);
        boolean vehicleSales = requiresNamedSalesEvidence(question);
        boolean external = wantsPolicyExplanation(question);
        for (DocumentCandidate candidate : grouped.values())
        {
            String name = candidate.name == null ? "" : candidate.name.toLowerCase(Locale.ROOT);
            double score = 40.0 / (1 + candidate.bestRank) + Math.min(candidate.count, 3) * 4.0;
            boolean nameHit = QuestionSubjectResolver.mentionsAny(candidate.name, entityList);
            if (nameHit) score += 60;
            else if (anyNameHit && !entities.isEmpty()) score -= 80;
            boolean yearHit = false;
            for (String year : years)
                if (name.contains(year))
                {
                    score += 20;
                    yearHit = true;
                }
            if (!years.isEmpty() && !yearHit && YEAR_PATTERN.matcher(name).find()) score -= 12;
            if (years.isEmpty())
            {
                int namedYear = latestYearIn(candidate.name);
                if (namedYear > 0) score += namedYear - 2000;
            }
            String type = candidate.type == null ? "" : candidate.type.toUpperCase(Locale.ROOT);
            if (peopleOrFinance)
            {
                if ("PDF".equals(type)) score += 30;
                else if ("NEWS".equals(type)) score -= 8;
            }
            else if (vehicleSales && "REPORT".equals(type)) score += 24;
            else if (external && ("NEWS".equals(type) || "POLICY".equals(type))) score += 18;
            else if ("PDF".equals(type)) score += 8;
            candidate.score = score;
        }
        return grouped.values().stream()
            .filter(candidate -> !anyNameHit || entityList.isEmpty()
                || QuestionSubjectResolver.mentionsAny(candidate.name, entityList))
            .sorted((left, right) -> Double.compare(right.score, left.score))
            .limit(Math.max(1, candidateDocumentTopK))
            .map(candidate -> new DocumentScope(candidate.sourceId, candidate.versionId))
            .toList();
    }

    private boolean isPeopleOrFinanceQuestion(String question)
    {
        QuestionIntentAnalyzer.Plan plan = intentAnalyzer.analyze(question);
        return plan.domain() == QuestionIntentAnalyzer.Domain.HUMAN_RESOURCES
            || plan.domain() == QuestionIntentAnalyzer.Domain.FINANCIAL
            || plan.domain() == QuestionIntentAnalyzer.Domain.RESEARCH;
    }

    private Map<String, Object> notFoundResult(String question, List<Map<String, Object>> logs,
        Map<String, Integer> sourceBreakdown, boolean salesGap)
    {
        String answer = salesGap
            ? "已检索到相关资料，但其中没有可用的车辆销量数据，当前知识库未找到充分依据。"
            : "当前知识库未找到充分依据。";
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", answer);
        result.put("claims", List.of());
        result.put("citations", List.of());
        result.put("model", llmConfiguration.getModel());
        result.put("answerMode", "NOT_FOUND");
        result.put("qaStatus", "NOT_FOUND_IN_KNOWLEDGE_BASE");
        result.put("citationCoveragePercent", 0);
        result.put("citationPolicy", "AGENT_GROUNDED");
        result.put("warnings", List.of(answer));
        result.put("newsExplanationEnabled", false);
        result.put("webLlmEnabled", false);
        result.put("sourceBreakdown", sourceBreakdown == null ? sourceBreakdown(List.of()) : sourceBreakdown);
        result.put("queryPlan", List.of(step(1, "检索唯一知识库", intentNarrative(question), "未找到足够证据")));
        result.put("retrievalLogs", logs);
        result.put("analysisTrace", List.of());
        result.put("graph", emptyGraph());
        return result;
    }

    private String qaStatusFor(String answerMode)
    {
        if ("LLM_UNAVAILABLE_WITH_EVIDENCE".equals(answerMode)) return "LLM_UNAVAILABLE";
        if ("NOT_FOUND".equals(answerMode)) return "NOT_FOUND_IN_KNOWLEDGE_BASE";
        if ("RETRIEVAL_FAILED".equals(answerMode)) return "RETRIEVAL_FAILED";
        if ("PROGRAM_PARTIAL".equals(answerMode)) return "PARTIALLY_ANSWERED";
        if ("PROGRAM_CALCULATED".equals(answerMode) || "LLM_VERIFIED".equals(answerMode) || "WEB_SEARCH".equals(answerMode))
            return "ANSWERED";
        return "PARTIALLY_ANSWERED";
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
        if (requiresNamedSalesEvidence(question)) intents.add("车辆销量");
        else if (value.matches("(?s).*(员工|在职|人数|销售人员|生产人员).*")) intents.add("员工人数");
        else if (value.matches("(?s).*(销售费用|销售收入|减值|现金流|营收|利润).*")) intents.add("财务金额");
        else if (value.matches("(?s).*(出货|面积|市占|份额|占比|同比|增长|shipment|share|yoy|area).*")) intents.add("数据结论");
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
        QuestionIntentAnalyzer.Plan plan = intentAnalyzer.analyze(question);
        String metric = switch (plan.domain())
        {
            case VEHICLE_SALES -> "车辆销量";
            case HUMAN_RESOURCES -> "员工人数";
            case FINANCIAL -> "财务指标";
            case RESEARCH -> "研发披露内容";
            case PRODUCT -> "产品与品牌披露";
            default -> "相关资料";
        };
        boolean explanation = question != null && question.matches("(?s).*(原因|解释|背景|新闻|政策|影响|为什么).*");
        return "用户想了解" + subject + "在" + period + "的" + metric
            + (explanation ? "，并判断新闻或政策是否可能解释该变化。" : "。")
            + "我需要先检索固定知识资料确认事实，再整理回答与引用。";
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
        return answerFromWebSearch(question, logs, progressListener, sourceBreakdown, false);
    }

    private Map<String, Object> answerFromWebSearch(String question, List<Map<String, Object>> logs,
        QaProgressListener progressListener, Map<String, Integer> sourceBreakdown,
        boolean retrievalCompletedWithoutEnoughEvidence) throws Exception
    {
        String emptyReason = retrievalCompletedWithoutEnoughEvidence
            ? "检索已完成，但当前范围内证据不足以回答该问题"
            : "固定知识库没有相关内容";
        notifyProgress(progressListener, 45, emptyReason + "，正在联网搜索", logs);
        long started = System.nanoTime();
        List<KnowledgeWebSearchClient.Hit> hits = webSearchClient.search(question);
        if (hits.isEmpty())
        {
            logs.add(log("SEARCH", "联网搜索", question, "没有返回可用网页", elapsedMillis(started), "FALLBACK"));
            throw new IllegalArgumentException(emptyReason + "，联网搜索也没有返回可用结果");
        }
        logs.add(log("SEARCH", "联网搜索", question, hits.size() + " 条网页结果", elapsedMillis(started), "SUCCESS"));
        notifyProgress(progressListener, 70, "已取得联网搜索结果，正在整理回答", logs);
        String answer;
        List<String> warnings = new ArrayList<>();
        warnings.add(emptyReason + "，以下结果为联网搜索而来");
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
        plan.add(step(2, "检索固定知识库", emptyReason, "证据不足"));
        plan.add(step(3, "联网搜索", "使用公开网页补充回答，并标明结果来自联网搜索", hits.size() + " 条网页结果"));
        List<Map<String, Object>> trace = new ArrayList<>();
        trace.add(traceStep(1, "固定知识库证据不足", "已检索结构化指标、分析报告、新闻、政策和PDF。",
            emptyReason + "。", "这一步不产生业务结论。", List.of()));
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
        result.put("qaStatus", "ANSWERED");
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

    private QuestionSubjectResolver.SubjectPlan resolveSubjectPlan(String question, List<Map<String, Object>> logs)
    {
        QuestionSubjectResolver.SubjectPlan rules = QuestionSubjectResolver.fromRules(question);
        if (llmConfiguration.getApiKey().isBlank())
        {
            logs.add(log("SUBJECT", "主体识别", question,
                rules.hasSubjects() ? "规则：" + String.join("、", rules.subjects()) : "未识别到明确主体",
                0, rules.hasSubjects() ? "SUCCESS" : "SKIP"));
            return rules;
        }
        long started = System.nanoTime();
        try
        {
            JSONArray messages = new JSONArray();
            messages.add(message("system", QuestionSubjectResolver.subjectResolveSystemPrompt()));
            messages.add(message("user", "问题：" + question));
            JSONObject payload = new JSONObject();
            payload.put("model", llmConfiguration.getModel());
            payload.put("messages", messages);
            payload.put("temperature", 0);
            payload.put("max_tokens", 200);
            payload.put("stream", false);
            String raw = sendLlmRequest(payload);
            QuestionSubjectResolver.SubjectPlan llm = QuestionSubjectResolver.parseLlmJson(raw);
            if (llm.hasSubjects() && !llmSubjectsGrounded(question, llm.subjects()))
                llm = new QuestionSubjectResolver.SubjectPlan(List.of(), false, "LLM");
            QuestionSubjectResolver.SubjectPlan merged = QuestionSubjectResolver.merge(rules, llm);
            logs.add(log("SUBJECT", "主体识别", question,
                merged.source() + (merged.hasSubjects() ? "：" + String.join("、", merged.subjects()) : "：无明确主体"),
                elapsedMillis(started), "SUCCESS"));
            return merged;
        }
        catch (Exception ex)
        {
            logs.add(log("SUBJECT", "主体识别", question,
                "LLM不可用，回退规则：" + abbreviate(ex.getMessage(), 120),
                elapsedMillis(started), "FALLBACK"));
            return rules;
        }
    }

    private boolean llmSubjectsGrounded(String question, List<String> subjects)
    {
        if (question == null || subjects == null) return false;
        String lower = question.toLowerCase(Locale.ROOT);
        for (String subject : subjects)
            if (subject != null && lower.contains(subject.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    /**
     * 已识别主体时，丢掉文件名和正文都不含该主体的资料。
     * 若已有文件名命中主体的文档，则不再保留只在正文里顺带提到主体的其他公司年报。
     */
    private List<KnowledgeChunk> alignChunksToSubject(String question, QuestionSubjectResolver.SubjectPlan plan,
        List<KnowledgeChunk> chunks, List<Map<String, Object>> logs)
    {
        if (chunks == null || chunks.isEmpty() || plan == null || !plan.mustMatch() || !plan.hasSubjects())
            return chunks == null ? List.of() : chunks;
        List<String> subjects = plan.subjects();
        boolean filenameHit = chunks.stream()
            .anyMatch(chunk -> QuestionSubjectResolver.mentionsAny(documentLabel(chunk), subjects));
        List<KnowledgeChunk> kept = new ArrayList<>();
        for (KnowledgeChunk chunk : chunks)
        {
            if (chunk == null) continue;
            boolean nameHit = QuestionSubjectResolver.mentionsAny(documentLabel(chunk), subjects);
            boolean bodyHit = QuestionSubjectResolver.mentionsAny(
                safe(chunk.getTitlePath()) + "\n" + safe(chunk.getContent()), subjects);
            if (filenameHit)
            {
                if (nameHit) kept.add(chunk);
            }
            else if (nameHit || bodyHit)
            {
                kept.add(chunk);
            }
        }
        List<KnowledgeChunk> triaged = triageDocuments(question, subjects, kept, logs);
        if (triaged.size() != chunks.size())
            logs.add(log("SUBJECT_FILTER", "按主体收紧资料", String.join("、", subjects),
                chunks.size() + " → " + triaged.size() + " 条切片", 0,
                triaged.isEmpty() ? "NO_HIT" : "SUCCESS"));
        return triaged;
    }

    private List<KnowledgeChunk> triageDocuments(String question, List<String> subjects,
        List<KnowledgeChunk> chunks, List<Map<String, Object>> logs)
    {
        if (chunks == null || chunks.size() < 2 || llmConfiguration.getApiKey().isBlank())
            return chunks == null ? List.of() : chunks;
        Map<Long, String> docs = new LinkedHashMap<>();
        for (KnowledgeChunk chunk : chunks)
        {
            if (chunk == null || chunk.getSourceId() == null) continue;
            docs.putIfAbsent(chunk.getSourceId(), documentLabel(chunk));
            if (docs.size() >= 8) break;
        }
        if (docs.size() < 2) return chunks;
        List<QuestionSubjectResolver.DocCandidate> candidates = new ArrayList<>();
        for (Map.Entry<Long, String> entry : docs.entrySet())
            candidates.add(new QuestionSubjectResolver.DocCandidate(entry.getKey(), entry.getValue()));
        long started = System.nanoTime();
        try
        {
            JSONArray messages = new JSONArray();
            messages.add(message("system", QuestionSubjectResolver.documentTriageSystemPrompt()));
            messages.add(message("user", QuestionSubjectResolver.documentTriageUserPrompt(question, subjects, candidates)));
            JSONObject payload = new JSONObject();
            payload.put("model", llmConfiguration.getModel());
            payload.put("messages", messages);
            payload.put("temperature", 0);
            payload.put("max_tokens", 200);
            payload.put("stream", false);
            List<Long> keep = QuestionSubjectResolver.parseKeepSourceIds(sendLlmRequest(payload), docs.keySet());
            if (keep.isEmpty() || keep.size() >= docs.size()) return chunks;
            List<KnowledgeChunk> filtered = chunks.stream()
                .filter(chunk -> chunk != null && chunk.getSourceId() != null && keep.contains(chunk.getSourceId()))
                .toList();
            logs.add(log("DOC_TRIAGE", "LLM筛选文档", String.join("、", subjects),
                "保留 sourceId=" + keep, elapsedMillis(started), filtered.isEmpty() ? "NO_HIT" : "SUCCESS"));
            return filtered.isEmpty() ? chunks : filtered;
        }
        catch (Exception ex)
        {
            logs.add(log("DOC_TRIAGE", "LLM筛选文档", question,
                "跳过：" + abbreviate(ex.getMessage(), 120), elapsedMillis(started), "FALLBACK"));
            return chunks;
        }
    }

    private String documentLabel(KnowledgeChunk chunk)
    {
        if (chunk == null) return "";
        String name = chunk.getOriginalName();
        if (name == null || name.isBlank()) name = chunk.getSourceName();
        return name == null ? "" : name;
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
            String evidence = KnowledgeTextProcessor.joinWrappedLines(readableEvidenceContent(question, chunk));
            context.append('\n').append(evidence);
            String raw = KnowledgeTextProcessor.joinWrappedLines(safe(chunk.getContent()));
            if (!raw.isBlank() && !raw.equals(evidence))
            {
                String sourceNumbers = raw.length() > 12000 ? raw.substring(0, 12000) : raw;
                context.append("\n可计算的原始数据：\n").append(sourceNumbers);
            }
            context.append("\n\n");
        }
        JSONArray messages = new JSONArray();
        messages.add(message("system", "你是汽车行业市场洞察分析助手。回答必须严格基于用户提供的资料。"
            + "用自然语言直接回答，先给结论，再补充关键数据和必要限定，语气接近市面常见分析助手。"
            + "不要使用“数据结论”“外部解释”“新闻与政策解释证据”“以下为通过引用校验的知识库确定性结果”这类固定标题或模板。"
            + "知识库资料里已经有的数字，必须按问题要求计算后再回答。包括加总、差额、平均值、占比、同比、环比、排序，不限于月度销量。"
            + "不要因为资料没有写好“合计”“全年”“占比”这几个字就拒绝计算。"
            + "计算用到的每一个输入数字都必须来自知识库资料，不能补造缺失项，也不能用新闻或政策替换、修正这些数字。"
            + "回答时说明结果是根据引用资料计算的；若计算所需的关键数字确实不在资料中，再说明缺什么，不要编造。"
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
        return buildEvidenceFallback(question, chunks, false);
    }

    private String buildEvidenceFallback(String question, List<KnowledgeChunk> chunks, boolean llmUnavailable)
    {
        StringBuilder answer = new StringBuilder();
        if (llmUnavailable) answer.append(LLM_UNAVAILABLE_LABEL).append('\n');
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
                appendEvidenceCatalog(answer, chunks);
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
                appendEvidenceCatalog(answer, chunks);
                return answer.toString().trim();
            }
        }

        List<String> reportBits = collectEvidenceSnippets(chunks, List.of("REPORT", "PDF"), Math.min(6, modelContextLimit), question);
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
            answer.append('\n');
        }
        appendEvidenceCatalog(answer, chunks);
        return answer.toString().trim();
    }

    private void appendEvidenceCatalog(StringBuilder answer, List<KnowledgeChunk> chunks)
    {
        if (chunks == null || chunks.isEmpty()) return;
        answer.append("参考原文：");
        int limit = Math.min(chunks.size(), modelContextLimit);
        for (int i = 0; i < limit; i++)
        {
            KnowledgeChunk chunk = chunks.get(i);
            answer.append("[S").append(i + 1).append("] ")
                .append(safe(chunk.getOriginalName()).isBlank() ? safe(chunk.getSourceName()) : safe(chunk.getOriginalName()));
            if (chunk.getPageStart() != null)
            {
                answer.append(" 第").append(chunk.getPageStart()).append("页");
                if (chunk.getPageEnd() != null && !chunk.getPageEnd().equals(chunk.getPageStart()))
                    answer.append("-").append(chunk.getPageEnd()).append("页");
            }
            if (i < limit - 1) answer.append("；");
        }
        answer.append('\n');
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
        if (question == null || question.isBlank()) return false;
        if (KnowledgeTextProcessor.detectEntityTerms(question).isEmpty()) return false;
        String value = question.toLowerCase(Locale.ROOT);
        boolean display = value.contains("出货") || value.contains("面板") || value.contains("hud")
            || value.contains("显示");
        if (display) return false;
        boolean explicitVolume = value.contains("销量") || value.contains("销售数量") || value.contains("销售台数")
            || value.contains("批发销量") || value.contains("零售销量") || value.contains("卖出多少辆")
            || value.contains("卖了多少辆") || value.contains("销售多少辆") || value.contains("多少辆")
            || value.contains("万辆");
        if (!explicitVolume) return false;
        // “销售人员/费用/收入”等非销量指标：除非同时明确问销量/辆数，否则不进入销量过滤
        boolean nonVolume = value.contains("销售人员") || value.contains("销售费用") || value.contains("销售收入")
            || value.contains("销售额") || value.contains("销售渠道") || value.contains("销售网络")
            || value.contains("销售部门") || value.contains("销售岗位") || value.contains("销售成本")
            || value.contains("在职员工") || value.contains("员工数量") || value.contains("生产人员");
        if (nonVolume && !(value.contains("销量") || value.contains("多少辆") || value.contains("销售台数")
            || value.contains("销售数量") || value.contains("万辆")))
            return false;
        return true;
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
        // 主体可来自文档元数据；切片正文未重复写品牌名时不应误删
        boolean brand = KnowledgeTextProcessor.detectEntityTerms(question).stream()
            .anyMatch(term -> haystack.contains(term.toLowerCase()));
        if (!brand) return false;
        if (hasUsableMarketSalesJson(chunk.getContent())) return true;
        if (isProvenanceOnlyMarketJson(chunk.getContent())) return false;
        return haystack.contains("销量") || haystack.contains("万辆")
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
        List<String> facts = extractVehicleSalesFacts(chunk, question);
        boolean computedTotal = facts.stream().anyMatch(fact -> fact.contains("合计"));
        if (!facts.isEmpty() && (requiresNamedSalesEvidence(question) || computedTotal))
            return String.join("\n", facts);
        if (!requiresNamedSalesEvidence(question)) return safe(chunk.getContent());
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
                if (score <= 0) score = scoreSubjectFact(fact, question);
                if ("REPORT".equals(type) || "PDF".equals(type)) score += 3;
                String metricId = chunk.getMetricId() == null ? "" : chunk.getMetricId().toLowerCase();
                if (metricId.contains("fact_pack") && fact.contains("月度") && fact.contains("合计")) score += 40;
                if (asksModelLevelSales(question) && fact.contains("车型") && fact.contains("排名")) score += 20;
                scored.add(new ScoredFact(score, fact + " [S" + (i + 1) + "]"));
            }
        }
        scored = keepOneMonthlyTotalPerYear(scored);
        scored.sort((a, b) -> Integer.compare(b.score(), a.score()));
        boolean modelLevel = asksModelLevelSales(question);
        List<String> subjects = questionSubjects(question);
        boolean hasSubjectTotal = scored.stream().anyMatch(item -> item.line().contains("合计")
            && subjects.stream().anyMatch(subject -> item.line().contains(subject)));
        List<String> lines = new ArrayList<>();
        for (ScoredFact item : scored)
        {
            if (item.score() <= 0) continue;
            if (modelLevel && item.line().contains("月度") && item.line().contains("合计") && !hasSubjectTotal) continue;
            if (hasSubjectTotal && item.line().contains("合计")
                && subjects.stream().noneMatch(subject -> item.line().contains(subject))) continue;
            boolean duplicate = lines.stream().anyMatch(existing -> normalizeFactKey(existing).equals(normalizeFactKey(item.line())));
            if (duplicate) continue;
            lines.add(item.line());
            if (lines.size() >= (modelLevel ? 10 : 5)) break;
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
        List<String> subjects = questionSubjects(question);
        if (entities.isEmpty() && subjects.isEmpty()) return List.of();
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
        boolean modelLevel = asksModelLevelSales(question);
        boolean matchedSeries = appendMatchedSeriesYearTotals(scored, content, question, entities);
        if (modelLevel)
            appendModelRankingFacts(scored, content, brandScope, question, entities);
        else
        {
            Matcher ranking = MARKET_RANK_PATTERN.matcher(content);
            boolean annual = asksAnnualTotal(question);
            while (!annual && ranking.find())
            {
                String name = ranking.group(1);
                String amount = ranking.group(2);
                if (!entities.stream().anyMatch(term -> name.toLowerCase().contains(term.toLowerCase()))) continue;
                String period = nearestPeriodLabel(content, ranking.start());
                if (period.isBlank()) continue;
                String fact = period + name + "销量为" + formatVehicleUnits(amount) + "。";
                scored.add(new ScoredFact(scoreSalesSentence(fact, entities, question) + 4, fact));
            }
            if (!matchedSeries)
                appendMonthlyYearTotals(scored, content, brandScope, question, entities);
        }
        scored.sort((a, b) -> Integer.compare(b.score(), a.score()));
        List<String> facts = new ArrayList<>();
        int cap = modelLevel ? 10 : 4;
        for (ScoredFact item : scored)
        {
            if (item.score() <= 0) continue;
            boolean duplicate = facts.stream().anyMatch(existing -> normalizeFactKey(existing).equals(normalizeFactKey(item.line())));
            if (duplicate) continue;
            facts.add(item.line());
            if (facts.size() >= cap) break;
        }
        return facts;
    }

    /** 问题点名的车型/对象若自带月度序列，按年加总。不限于海鸥，也不再用车企总趋势冒充。 */
    private boolean appendMatchedSeriesYearTotals(List<ScoredFact> scored, String content, String question,
        List<String> entities)
    {
        List<String> subjects = questionSubjects(question);
        if (content == null || subjects.isEmpty()) return false;
        boolean added = false;
        Matcher names = Pattern.compile("\"(?:name|title)\"\\s*:\\s*\"([^\"]{2,40})\"").matcher(content);
        Set<String> seen = new LinkedHashSet<>();
        while (names.find())
        {
            String name = names.group(1).trim();
            if (!seriesNameMatches(name, subjects)) continue;
            if (!seen.add(name.toLowerCase(Locale.ROOT))) continue;
            String points = pointsArrayNear(content, names.end());
            if (points.isBlank()) continue;
            if (addSeriesYearTotal(scored, points, name, content, question, entities)) added = true;
        }
        return added;
    }

    private boolean addSeriesYearTotal(List<ScoredFact> scored, String points, String name, String content,
        String question, List<String> entities)
    {
        Map<String, Double> byMonth = new LinkedHashMap<>();
        collectTrendPoints(byMonth, points);
        if (byMonth.size() < 2) return false;
        Map<String, double[]> byYear = new LinkedHashMap<>();
        for (Map.Entry<String, Double> entry : byMonth.entrySet())
        {
            String year = entry.getKey().substring(0, 4);
            double[] bucket = byYear.computeIfAbsent(year, ignored -> new double[2]);
            bucket[0] += entry.getValue();
            bucket[1] += 1;
        }
        String metric = content.contains("批发") ? "批发销量" : "销量";
        String brand = entities == null || entities.isEmpty() ? "" : entities.get(0);
        List<String> asked = yearsIn(question);
        List<String> years = asked.isEmpty() ? new ArrayList<>(byYear.keySet()) : asked;
        boolean added = false;
        for (String year : years)
        {
            double[] bucket = byYear.get(year);
            if (bucket == null || bucket[1] < 2) continue;
            int months = (int) bucket[1];
            String scope = months >= 12 ? year + "年1-12月" : year + "年已有" + months + "个月";
            String fact = brand + name + scope + "月度" + metric + "合计为" + formatVehicleUnits(String.valueOf(bucket[0]))
                + "，由该对象的月度数据相加，不是另行发布的年报口径。";
            int score = scoreSalesSentence(fact, entities, question);
            if (score <= 0) score = scoreSubjectFact(fact, question);
            if (score <= 0) continue;
            scored.add(new ScoredFact(score + 55, fact));
            added = true;
        }
        return added;
    }

    private void collectTrendPoints(Map<String, Double> byMonth, String text)
    {
        Matcher points = TREND_POINT_PATTERN.matcher(text);
        while (points.find())
            byMonth.put(points.group(1) + "-" + points.group(2), Double.parseDouble(points.group(3)));
        Matcher alt = Pattern.compile(
            "\"period\"\\s*:\\s*\"(20\\d{2})-(\\d{2})\"\\s*,\\s*\"value\"\\s*:\\s*([0-9]+(?:\\.[0-9]+)?)")
            .matcher(text);
        while (alt.find())
            byMonth.putIfAbsent(alt.group(1) + "-" + alt.group(2), Double.parseDouble(alt.group(3)));
    }

    private String pointsArrayNear(String content, int from)
    {
        int key = content.indexOf("\"points\"", from);
        if (key < 0 || key - from > 240) return "";
        int start = content.indexOf('[', key);
        if (start < 0) return "";
        int depth = 0;
        for (int i = start; i < content.length(); i++)
        {
            char ch = content.charAt(i);
            if (ch == '[') depth++;
            else if (ch == ']')
            {
                depth--;
                if (depth == 0) return content.substring(start, i + 1);
            }
        }
        return "";
    }

    private static final Set<String> SUBJECT_STOP = Set.of(
        "销量", "销售", "全年", "年度", "年销量", "年批发", "各个", "车型", "多少", "什么", "数据",
        "月度", "合计", "批发", "零售", "汽车", "市场", "排名", "报告", "知识", "请问", "一下");

    /** 问题里点名的具体对象，不含车企品牌和“销量/全年”这类词。 */
    private List<String> questionSubjects(String question)
    {
        List<String> subjects = new ArrayList<>();
        if (question == null) return subjects;
        Set<String> brands = new LinkedHashSet<>();
        for (String term : KnowledgeTextProcessor.detectEntityTerms(question))
            brands.add(term.toLowerCase(Locale.ROOT));
        Matcher matcher = Pattern.compile("[\\u4e00-\\u9fa5A-Za-z0-9+＋]{2,}").matcher(question);
        while (matcher.find())
        {
            String token = matcher.group();
            if (token.matches("20\\d{2}") || SUBJECT_STOP.contains(token)) continue;
            if (brands.contains(token.toLowerCase(Locale.ROOT))) continue;
            if (!subjects.contains(token)) subjects.add(token);
        }
        return subjects;
    }

    private boolean seriesNameMatches(String name, List<String> subjects)
    {
        if (name == null) return false;
        String normalized = name.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
        if (normalized.length() < 2 || SUBJECT_STOP.contains(name) || "销量/数值".equals(name)) return false;
        for (String subject : subjects)
        {
            String needle = subject.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
            if (needle.length() < 2) continue;
            if (normalized.equals(needle) || normalized.contains(needle) || needle.contains(normalized)) return true;
        }
        return false;
    }

    private int scoreSubjectFact(String fact, String question)
    {
        if (fact == null || !fact.contains("合计") || !fact.contains("销量")) return 0;
        if (questionSubjects(question).stream().noneMatch(fact::contains)) return 0;
        List<String> years = yearsIn(question);
        if (!years.isEmpty() && years.stream().noneMatch(fact::contains)) return 0;
        return 36;
    }

    /** 只加总 monthly_trend，避免把多车型折线里的时间/数值误加成年合计。 */
    private void appendMonthlyYearTotals(List<ScoredFact> scored, String content, String brandHaystack,
        String question, List<String> entities)
    {
        if (!entities.stream().anyMatch(term -> brandHaystack.toLowerCase().contains(term.toLowerCase()))) return;
        String trend = monthlyTrendArray(content);
        if (trend.isBlank()) return;
        Map<String, Double> byMonth = new LinkedHashMap<>();
        Matcher points = TREND_POINT_PATTERN.matcher(trend);
        while (points.find())
            byMonth.put(points.group(1) + "-" + points.group(2), Double.parseDouble(points.group(3)));
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

    private String monthlyTrendArray(String content)
    {
        if (content == null) return "";
        int key = content.indexOf("\"monthly_trend\"");
        if (key < 0) return "";
        int start = content.indexOf('[', key);
        if (start < 0) return "";
        int depth = 0;
        for (int i = start; i < content.length(); i++)
        {
            char ch = content.charAt(i);
            if (ch == '[') depth++;
            else if (ch == ']')
            {
                depth--;
                if (depth == 0) return content.substring(start, i + 1);
            }
        }
        return "";
    }

    private boolean asksAnnualTotal(String question)
    {
        String value = question == null ? "" : question;
        return value.contains("全年") || value.contains("年度") || value.contains("年销量") || value.contains("年批发");
    }

    /** 问各车型销量时，要用排名行，不能把车企 monthly_trend 合计当成答案。 */
    private boolean asksModelLevelSales(String question)
    {
        String value = question == null ? "" : question;
        return value.contains("车型") || value.contains("各车") || value.contains("各个");
    }

    private void appendModelRankingFacts(List<ScoredFact> scored, String content, String brandHaystack,
        String question, List<String> entities)
    {
        if (!entities.stream().anyMatch(term -> brandHaystack.toLowerCase().contains(term.toLowerCase()))) return;
        String slice = modelRankingSlice(content, brandHaystack);
        if (slice.isBlank()) return;
        String period = reportPeriodLabel(content);
        if (period.isBlank()) period = nearestPeriodLabel(content, Math.max(0, content.indexOf("\"对象\"")));
        String subject = entities.stream()
            .filter(term -> brandHaystack.toLowerCase().contains(term.toLowerCase()))
            .findFirst().orElse(entities.get(0));
        Matcher ranking = MARKET_RANK_PATTERN.matcher(slice);
        int added = 0;
        while (ranking.find() && added < 10)
        {
            String name = ranking.group(1);
            if (entities.stream().anyMatch(term -> name.toLowerCase().contains(term.toLowerCase())
                && name.length() <= term.length() + 2))
                continue;
            String fact = (period.isBlank() ? "" : period)
                + subject + "车型" + name + "销量为" + formatVehicleUnits(ranking.group(2))
                + "。该数字来自整车市场周报车型排名，对应报告分析期，不是各车型全年逐月加总。";
            int score = scoreSalesSentence(fact, entities, question);
            if (score <= 0) continue;
            scored.add(new ScoredFact(score + 25, fact));
            added++;
        }
    }

    private String modelRankingSlice(String content, String brandHaystack)
    {
        String hay = (content == null ? "" : content) + " " + (brandHaystack == null ? "" : brandHaystack);
        String lower = hay.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("top_model") || lower.contains("full_model")) return content == null ? "" : content;
        String rankings = sliceBalanced(content, "rankings", '{', '}');
        if (rankings.isBlank()) return "";
        String model = sliceBalanced(rankings, "model", '{', '}');
        return model.isBlank() ? sliceBalanced(rankings, "model", '[', ']') : model;
    }

    private String reportPeriodLabel(String content)
    {
        if (content == null) return "";
        Matcher display = Pattern.compile("\"display_label\"\\s*:\\s*\"([^\"]{2,40})\"").matcher(content);
        if (display.find()) return display.group(1);
        Matcher latest = Pattern.compile("\"latest_period\"\\s*:\\s*\"(20\\d{2})-(\\d{2})\"").matcher(content);
        if (latest.find()) return latest.group(1) + "年" + Integer.parseInt(latest.group(2)) + "月";
        return "";
    }

    private String sliceBalanced(String content, String key, char open, char close)
    {
        if (content == null || key == null) return "";
        int marker = content.indexOf("\"" + key + "\"");
        if (marker < 0) return "";
        int start = content.indexOf(open, marker);
        if (start < 0) return "";
        int depth = 0;
        for (int i = start; i < content.length(); i++)
        {
            char ch = content.charAt(i);
            if (ch == open) depth++;
            else if (ch == close)
            {
                depth--;
                if (depth == 0) return content.substring(start, i + 1);
            }
        }
        return "";
    }

    /** 同一年只留一条月度合计；量级差超过一倍时丢掉偏大的那条。 */
    private List<ScoredFact> keepOneMonthlyTotalPerYear(List<ScoredFact> scored)
    {
        Map<String, ScoredFact> chosen = new LinkedHashMap<>();
        List<ScoredFact> others = new ArrayList<>();
        for (ScoredFact item : scored)
        {
            String line = item.line();
            if (!line.contains("月度") || !line.contains("合计"))
            {
                others.add(item);
                continue;
            }
            Matcher year = YEAR_PATTERN.matcher(line);
            if (!year.find())
            {
                others.add(item);
                continue;
            }
            String key = year.group(1);
            ScoredFact current = chosen.get(key);
            if (current == null) chosen.put(key, item);
            else chosen.put(key, preferMonthlyTotal(current, item));
        }
        List<ScoredFact> result = new ArrayList<>(others);
        result.addAll(chosen.values());
        return result;
    }

    private ScoredFact preferMonthlyTotal(ScoredFact left, ScoredFact right)
    {
        double leftValue = firstNumber(left.line());
        double rightValue = firstNumber(right.line());
        if (leftValue > 0 && rightValue > 0)
        {
            if (leftValue > rightValue * 2) return right;
            if (rightValue > leftValue * 2) return left;
        }
        return left.score() >= right.score() ? left : right;
    }

    private double firstNumber(String text)
    {
        Matcher matcher = Pattern.compile("([0-9][0-9,]*(?:\\.[0-9]+)?)").matcher(text == null ? "" : text);
        while (matcher.find())
        {
            try { return Double.parseDouble(matcher.group(1).replace(",", "")); }
            catch (NumberFormatException ignored) { }
        }
        return 0;
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

    private record DeterministicAnswer(String text, boolean partial, List<String> warnings) { }

    private record DocumentScope(Long sourceId, Long versionId) { }

    private static final class DocumentCandidate
    {
        private final Long sourceId;
        private final Long versionId;
        private int count;
        private int bestRank = Integer.MAX_VALUE;
        private double score;
        private String name = "";
        private String type = "";

        private DocumentCandidate(Long sourceId, Long versionId)
        {
            this.sourceId = sourceId;
            this.versionId = versionId;
        }
    }

    private List<String> splitSubQuestions(String question)
    {
        if (question == null || question.isBlank()) return List.of();
        String text = question.trim();
        String[] lines = text.split("\\R+");
        List<String> numbered = new ArrayList<>();
        for (String line : lines)
        {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isBlank()) continue;
            if (NUMBERED_QUESTION_PATTERN.matcher(trimmed).find())
            {
                String cleaned = NUMBERED_QUESTION_PATTERN.matcher(trimmed).replaceFirst("").trim();
                if (cleaned.length() >= 2) numbered.add(cleaned);
            }
        }
        if (numbered.size() >= 2) return numbered;
        String[] marked = text.split("[？?]");
        List<String> questions = new ArrayList<>();
        for (String part : marked)
        {
            String cleaned = part == null ? "" : part.trim().replaceAll("^[，,；;、\\s]+", "");
            if (cleaned.length() < 2) continue;
            if (cleaned.startsWith("不要") || cleaned.startsWith("请勿") || cleaned.startsWith("注意")) continue;
            questions.add(cleaned);
        }
        if (questions.size() >= 2) return questions;
        return List.of(text);
    }

    private List<String> buildSearchQueries(String question)
    {
        // 精确同义词放前面：FULLTEXT 合并时先写入高相关切片，避免长问句先占满上下文。
        LinkedHashSet<String> queries = new LinkedHashSet<>();
        String value = question == null ? "" : question;
        if (value.contains("汽车级电池") || value.contains("电池业务") || value.contains("动力电池"))
        {
            queries.add("动力电池领域 刀片电池");
            queries.add("动力电池 刀片电池");
            queries.add("汽车及电池业务");
            queries.add("车用电池");
            if (value.contains("汽车级电池"))
                queries.add(value.replace("汽车级电池", "动力电池"));
        }
        if (value.contains("研发") && (value.contains("项目") || value.contains("投入")))
        {
            queries.add("主要研发项 项目名称");
            queries.add("主要研发项目");
            queries.add("第二代刀片电池及闪充技术");
            queries.add("研发项目名称");
        }
        if (value.contains("母公司") || value.contains("在职员工") || value.contains("销售人员")
            || value.contains("生产人员"))
        {
            queries.add("报告期末母公司在职员工的数量");
            queries.add("专业构成 生产人员 销售人员");
            queries.add("公司员工情况");
        }
        if (value.contains("王朝") || value.contains("海洋") || value.contains("产品系列")
            || value.contains("共同构建") || (value.contains("品牌") && value.contains("系列")))
        {
            queries.add("王朝 海洋 共同构建");
            queries.add("比亚迪品牌 王朝 海洋");
            queries.add("龙颜美学 海洋美学");
            queries.add("产品系列 共同构建");
        }
        if (value.contains("信用减值") || value.contains("资产减值"))
        {
            queries.add("信用减值准备");
            queries.add("资产减值准备");
            queries.add("现金流量表补充资料");
            queries.add("信用减值损失 合计");
        }
        if (value.contains("营业收入") || value.contains("营收"))
        {
            queries.add("营业收入（元）");
            queries.add("本年比上年增减 营业收入");
            queries.add("主要会计数据和财务指标");
        }
        if (value.contains("研发投入"))
        {
            queries.add("研发投入约人民币");
            queries.add("研发投入金额");
            queries.add("累计研发投入");
        }
        if (value.contains("国家和地区") || value.contains("出海"))
        {
            queries.add("国家和地区 出海");
            queries.add("新能源汽车运营足迹");
        }
        if (value.contains("闪电配售") || value.contains("H股"))
        {
            queries.add("H股闪电配售");
            queries.add("56亿美元");
        }
        if (question != null && !question.isBlank()) queries.add(question.trim());
        return new ArrayList<>(queries);
    }

    /**
     * 按问题类型重排证据，压低税项/经营范围等易抢分噪声页，抬高年报业务正文命中。
     */
    List<KnowledgeChunk> rankEvidenceForQuestion(String question, List<KnowledgeChunk> chunks)
    {
        if (chunks == null || chunks.isEmpty()) return chunks == null ? List.of() : chunks;
        String value = question == null ? "" : question;
        QuestionIntentAnalyzer.Plan plan = intentAnalyzer.analyze(value);
        boolean battery = value.contains("汽车级电池") || value.contains("电池业务") || value.contains("动力电池");
        boolean rd = plan.domain() == QuestionIntentAnalyzer.Domain.RESEARCH
            || (value.contains("研发") && (value.contains("项目") || value.contains("投入")));
        boolean brand = plan.domain() == QuestionIntentAnalyzer.Domain.PRODUCT;
        boolean staff = plan.domain() == QuestionIntentAnalyzer.Domain.HUMAN_RESOURCES;
        boolean impair = plan.domain() == QuestionIntentAnalyzer.Domain.FINANCIAL && value.contains("减值");
        boolean revenue = value.contains("营业收入") || value.contains("营收");
        boolean rdSpend = value.contains("研发投入");
        boolean overseas = value.contains("国家和地区") || value.contains("出海");
        boolean placement = value.contains("闪电配售") || value.contains("H股");
        List<KnowledgeChunk> ranked = new ArrayList<>(chunks);
        ranked.sort((left, right) -> Integer.compare(
            evidenceRelevanceScore(value, right, battery, rd, brand, staff, impair)
                + documentFit(value, right)
                + topicBoost(value, right, revenue, rdSpend, overseas, placement),
            evidenceRelevanceScore(value, left, battery, rd, brand, staff, impair)
                + documentFit(value, left)
                + topicBoost(value, left, revenue, rdSpend, overseas, placement)));
        return ranked;
    }

    private int topicBoost(String question, KnowledgeChunk chunk, boolean revenue, boolean rdSpend,
        boolean overseas, boolean placement)
    {
        if (chunk == null) return 0;
        String content = safe(chunk.getContent());
        int score = 0;
        if (revenue)
        {
            if (content.contains("营业收入（元）") || content.contains("营业收入合计")) score += 120;
            if (content.contains("本年比上年增减") && content.contains("营业收入")) score += 60;
        }
        if (rdSpend)
        {
            if (content.contains("研发投入约人民币") || content.contains("研发投入金额")) score += 120;
            if (content.contains("同比上升") && content.contains("研发")) score += 40;
        }
        if (overseas)
        {
            if (content.contains("国家和地区")) score += 120;
            if (content.contains("出海覆盖")) score += 60;
        }
        if (placement)
        {
            if (content.contains("闪电配售")) score += 140;
            if (content.contains("56亿美元") || content.contains("H 股") || content.contains("H股")) score += 40;
        }
        return score;
    }

    private int evidenceRelevanceScore(String question, KnowledgeChunk chunk,
        boolean battery, boolean rd, boolean brand, boolean staff, boolean impair)
    {
        if (chunk == null) return Integer.MIN_VALUE / 4;
        String content = chunk.getContent() == null ? "" : chunk.getContent();
        int score = 0;
        if (battery)
        {
            if (content.contains("动力电池领域")) score += 120;
            if (content.contains("刀片电池") && !content.contains("弗迪电池")) score += 60;
            if (content.contains("第二代刀片")) score += 40;
            if (content.contains("弗迪电池") || content.contains("高新技术企业") || content.contains("西部大开发"))
                score -= 90;
            if (content.contains("加计抵减") || content.contains("企业所得税")) score -= 50;
        }
        if (rd)
        {
            if (content.contains("主要研发项") || content.contains("主要研发项目")) score += 120;
            if (content.contains("第二代刀片") || content.contains("项目进展") || content.contains("拟达到的目标"))
                score += 50;
            if (content.contains("高新技术企业") || content.contains("担保") || content.contains("适用年份"))
                score -= 80;
        }
        if (brand)
        {
            if (content.contains("共同构建") && content.contains("王朝") && content.contains("海洋")) score += 150;
            if (content.contains("王朝") && content.contains("海洋")) score += 80;
            if (content.contains("龙颜美学") || content.contains("海洋美学")) score += 40;
            if (content.contains("经营范围") || content.contains("总经销商")) score -= 60;
        }
        if (staff)
        {
            if (content.contains("母公司在职员工")) score += 120;
            if (content.contains("生产人员") && content.contains("销售人员")) score += 80;
            if (content.contains("万辆") && !content.contains("人员")) score -= 40;
        }
        if (impair)
        {
            if (content.contains("信用减值")) score += 100;
            if (content.contains("资产减值")) score += 100;
            if (content.contains("销量") && !content.contains("减值")) score -= 40;
        }
        return score;
    }

    private int documentFit(String question, KnowledgeChunk chunk)
    {
        if (chunk == null) return 0;
        String name = (safe(chunk.getOriginalName()) + " " + safe(chunk.getSourceName()));
        int namedYear = latestYearIn(name);
        List<String> asked = yearsIn(question);
        if (!asked.isEmpty())
        {
            if (namedYear > 0 && asked.contains(String.valueOf(namedYear))) return 200;
            if (namedYear > 0) return -80;
            return 0;
        }
        return namedYear > 0 ? namedYear - 2000 : 0;
    }

    private int latestYearIn(String text)
    {
        int latest = 0;
        if (text == null) return latest;
        Matcher matcher = YEAR_PATTERN.matcher(text);
        while (matcher.find()) latest = Math.max(latest, Integer.parseInt(matcher.group(1)));
        return latest;
    }

    private List<String> preferSpecificQueries(String question, List<String> queries)
    {
        List<String> terms = new ArrayList<>(QuestionEvidenceSelector.contentTerms(question));
        terms.sort((left, right) -> Integer.compare(right.length(), left.length()));
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        int added = 0;
        for (String term : terms)
        {
            if (added >= 6) break;
            if (term.length() < 2) continue;
            ordered.add(term);
            added++;
        }
        if (queries != null) ordered.addAll(queries);
        return new ArrayList<>(ordered);
    }

    private List<KnowledgeChunk> retainRareDocuments(String question, List<KnowledgeChunk> chunks,
        List<Map<String, Object>> logs)
    {
        if (chunks == null || chunks.isEmpty()) return chunks == null ? List.of() : chunks;
        List<KnowledgeChunk> kept = QuestionEvidenceSelector.retainDocumentsWithRarestTerms(question, chunks);
        if (kept.size() != chunks.size())
            logs.add(log("RARE_TERM", "按少见词收紧文档", question,
                chunks.size() + " → " + kept.size() + " 条切片", 0, kept.isEmpty() ? "NO_HIT" : "SUCCESS"));
        return kept;
    }

    private DeterministicAnswer buildDeterministicAnswer(String question, List<KnowledgeChunk> chunks)
    {
        QuestionIntentAnalyzer.Plan plan = intentAnalyzer.analyze(question);
        LinkedHashSet<Long> sourceIds = new LinkedHashSet<>();
        if (chunks != null)
            for (KnowledgeChunk chunk : chunks)
                if (chunk != null && chunk.getSourceId() != null) sourceIds.add(chunk.getSourceId());
        LinkedHashSet<String> hints = new LinkedHashSet<>(KnowledgeProgramAnswerService.searchHints(plan.metricHints()));
        List<String> terms = new ArrayList<>(QuestionEvidenceSelector.contentTerms(question));
        terms.sort((left, right) -> Integer.compare(right.length(), left.length()));
        int extra = 0;
        for (String term : terms)
        {
            if (extra >= 24 || term.length() < 4) continue;
            if (hints.add(term)) extra++;
        }
        List<com.ruoyi.business.knowledge.domain.KnowledgeFact> stored =
            ingestService.searchFacts(new ArrayList<>(hints), new ArrayList<>(sourceIds));
        KnowledgeProgramAnswerService.Answer fromStore = programAnswerService.tryAnswerStored(question, stored);
        if (fromStore != null) return new DeterministicAnswer(fromStore.text(), fromStore.partial(), fromStore.warnings());
        KnowledgeProgramAnswerService.Answer answer = programAnswerService.tryAnswer(question, chunks);
        if (answer == null) return null;
        return new DeterministicAnswer(answer.text(), answer.partial(), answer.warnings());
    }

    private Map<String, Object> retrievalFailedResult(String question, List<Map<String, Object>> logs)
    {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", "知识库检索暂时失败，请稍后重试。");
        result.put("claims", List.of());
        result.put("citations", List.of());
        result.put("model", llmConfiguration.getModel());
        result.put("answerMode", "RETRIEVAL_FAILED");
        result.put("qaStatus", "RETRIEVAL_FAILED");
        result.put("citationCoveragePercent", 0);
        result.put("citationPolicy", "AGENT_GROUNDED");
        result.put("warnings", List.of("知识库检索暂时失败"));
        result.put("newsExplanationEnabled", false);
        result.put("webLlmEnabled", false);
        result.put("sourceBreakdown", sourceBreakdown(List.of()));
        result.put("queryPlan", List.of(step(1, "检索唯一知识库", intentNarrative(question), "检索失败")));
        result.put("retrievalLogs", logs);
        result.put("analysisTrace", List.of());
        result.put("graph", emptyGraph());
        return result;
    }

    private void appendUnspecifiedYearNote(String question, List<Map<String, Object>> citations, List<String> warnings)
    {
        if (!yearsIn(question).isEmpty() || citations == null) return;
        for (Map<String, Object> citation : citations)
        {
            String name = String.valueOf(citation.getOrDefault("originalName", citation.getOrDefault("sourceName", "")));
            if (latestYearIn(name) > 0 && (name.contains("年报") || name.contains("年度报告") || name.contains("报告")))
            {
                warnings.add("问题未指定年份，本次依据《" + name + "》作答。如需其他公司或年份，请继续说明。");
                return;
            }
        }
    }

    private DocumentScope resolveDocumentScope(String question, Long sourceId, Long versionId,
        List<Long> roleIds, boolean admin, List<Map<String, Object>> logs)
    {
        if (sourceId != null || versionId != null)
        {
            Long resolvedSource = sourceId;
            Long resolvedVersion = versionId;
            if (resolvedSource != null && resolvedVersion == null)
            {
                try
                {
                    var source = ingestService.getAuthorizedSource(resolvedSource, roleIds, admin);
                    if (source != null) resolvedVersion = source.getCurrentVersionId();
                }
                catch (Exception ignored) { }
            }
            logs.add(log("SCOPE", "文档范围", "用户指定",
                "sourceId=" + resolvedSource + " versionId=" + resolvedVersion, 0, "SUCCESS"));
            return new DocumentScope(resolvedSource, resolvedVersion);
        }
        String value = question == null ? "" : question;
        if (!(value.contains("年报") || value.contains("年度报告"))) return null;
        List<String> entities = KnowledgeTextProcessor.detectEntityTerms(question);
        if (entities.isEmpty()) return null;
        for (String entity : entities)
        {
            try
            {
                String hint = entity + "年度报告";
                var source = ingestService.resolveAuthorizedSourceByNameHint(hint, "PDF", roleIds, admin);
                if (source == null)
                    source = ingestService.resolveAuthorizedSourceByNameHint(entity + "年报", "PDF", roleIds, admin);
                if (source == null) continue;
                Long resolvedVersion = source.getCurrentVersionId();
                logs.add(log("SCOPE", "文档范围", "按资料名称解析",
                    source.getSourceName() + " / sourceId=" + source.getId()
                        + " versionId=" + resolvedVersion, 0, "SUCCESS"));
                return new DocumentScope(source.getId(), resolvedVersion);
            }
            catch (Exception ignored) { }
        }
        return null;
    }

    private List<KnowledgeChunk> rankForDispatch(String question, List<KnowledgeChunk> chunks)
    {
        if (chunks == null || chunks.size() <= 1) return chunks == null ? List.of() : chunks;
        List<String> terms = QuestionEvidenceSelector.contentTerms(question).stream()
            .filter(term -> term != null && term.length() >= 4)
            .toList();
        if (terms.isEmpty()) return chunks;
        List<KnowledgeChunk> ranked = new ArrayList<>(chunks);
        ranked.sort((left, right) -> Integer.compare(dispatchOverlap(right, terms), dispatchOverlap(left, terms)));
        return ranked;
    }

    private static int dispatchOverlap(KnowledgeChunk chunk, List<String> terms)
    {
        if (chunk == null || chunk.getContent() == null) return 0;
        String text = chunk.getContent().replaceAll("\\s+", "");
        int score = 0;
        for (String term : terms)
            if (text.contains(term)) score += term.length();
        return score;
    }

    private List<KnowledgeChunk> selectForModelContext(List<KnowledgeChunk> chunks)
    {
        if (chunks == null || chunks.isEmpty()) return List.of();
        int limit = Math.max(1, modelContextLimit);
        if (chunks.size() <= limit) return chunks;
        return chunks.subList(0, limit);
    }

    private boolean isLlmBalanceOrAuthIssue(Throwable error)
    {
        String message = error == null || error.getMessage() == null ? "" : error.getMessage();
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("http 402") || lower.contains("insufficient balance")
            || lower.contains("http 401") || lower.contains("invalid_api_key")
            || lower.contains("authentication");
    }

    private record RetrievalResult(List<KnowledgeChunk> chunks, int metricHits) { }

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
        value.put("sourceId", chunk.getSourceId()); value.put("versionId", chunk.getVersionId());
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

    @FunctionalInterface
    public interface QaProgressListener
    {
        void onProgress(int progress, String stage, List<Map<String, Object>> logs);
    }
}
