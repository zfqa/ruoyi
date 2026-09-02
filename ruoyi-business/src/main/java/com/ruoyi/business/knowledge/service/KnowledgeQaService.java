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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** 基于当前有效知识切片生成带可校验来源编号的回答。 */
@Service
public class KnowledgeQaService
{
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[S(\\d+)]");
    private final KnowledgeIngestService ingestService;
    private final HttpClient httpClient;
    private final String apiUrl;
    private final String model;
    private final String apiKey;

    public KnowledgeQaService(KnowledgeIngestService ingestService,
        @Value("${business.excel.llm.api-url:https://ark.cn-beijing.volces.com/api/v3/chat/completions}") String apiUrl,
        @Value("${business.excel.llm.model:doubao-seed-2-1-turbo-260628}") String model,
        @Value("${business.excel.llm.api-key:}") String apiKey)
    {
        this.ingestService = ingestService;
        this.apiUrl = apiUrl;
        this.model = model;
        this.apiKey = apiKey;
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    public Map<String, Object> ask(String question, String sourceType, List<Long> roleIds, boolean admin) throws Exception
    {
        if (question == null || question.trim().length() < 2) throw new IllegalArgumentException("问题至少2个字符");
        List<KnowledgeChunk> chunks = ingestService.search(question.trim(), sourceType, roleIds, admin, 8);
        if (chunks.isEmpty()) throw new IllegalArgumentException("当前有效知识版本中没有找到相关资料");
        if (apiKey == null || apiKey.isBlank())
            throw new IllegalStateException("未配置ARK_API_KEY，知识检索可用，但LLM问答暂不可用");

        String answer = callLlm(question.trim(), chunks);
        List<Integer> citedIndexes = validateCitations(answer, chunks.size());
        if (citedIndexes.isEmpty())
            throw new IllegalStateException("LLM回答未给出有效来源编号，已拒绝返回无来源结论");
        List<Map<String, Object>> citations = new ArrayList<>();
        for (Integer index : citedIndexes) citations.add(toCitation("S" + index, chunks.get(index - 1)));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("answer", answer);
        result.put("citations", citations);
        result.put("model", model);
        return result;
    }

    private String callLlm(String question, List<KnowledgeChunk> chunks) throws Exception
    {
        StringBuilder context = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++)
        {
            KnowledgeChunk chunk = chunks.get(i);
            context.append("[S").append(i + 1).append("] ").append(chunk.getSourceName()).append(" / ").append(chunk.getVersionNo());
            if (chunk.getPageStart() != null) context.append(" / PDF第").append(chunk.getPageStart()).append("页");
            if (chunk.getMetricId() != null && !chunk.getMetricId().isBlank()) context.append(" / 指标").append(chunk.getMetricId());
            context.append('\n').append(chunk.getContent()).append("\n\n");
        }
        JSONArray messages = new JSONArray();
        messages.add(message("system", "你是企业知识库问答助手。只能依据用户提供的资料回答；每个事实结论后必须标注一个或多个来源编号，如[S1]。资料不足时明确说资料不足，不得编造来源编号。"));
        messages.add(message("user", "问题：" + question + "\n\n可用资料：\n" + context));
        JSONObject payload = new JSONObject();
        payload.put("model", model); payload.put("messages", messages); payload.put("temperature", 0.1); payload.put("stream", false);
        HttpRequest request = HttpRequest.newBuilder(URI.create(apiUrl)).timeout(Duration.ofSeconds(90))
            .header("Authorization", "Bearer " + apiKey).header("Content-Type", "application/json")
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

    private JSONObject message(String role, String content)
    {
        JSONObject value = new JSONObject(); value.put("role", role); value.put("content", content); return value;
    }

    private Map<String, Object> toCitation(String label, KnowledgeChunk chunk)
    {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("citationLabel", label); value.put("id", chunk.getId());
        value.put("sourceName", chunk.getSourceName()); value.put("sourceType", chunk.getSourceType());
        value.put("versionNo", chunk.getVersionNo()); value.put("pageStart", chunk.getPageStart());
        value.put("pageEnd", chunk.getPageEnd()); value.put("sourceUrl", chunk.getSourceUrl());
        value.put("reportId", chunk.getReportId()); value.put("metricId", chunk.getMetricId());
        value.put("sourceSnippet", chunk.getSourceSnippet()); value.put("evidenceJson", chunk.getEvidenceJson());
        return value;
    }

    private List<Integer> validateCitations(String answer, int sourceCount)
    {
        List<Integer> indexes = new ArrayList<>();
        Matcher matcher = CITATION_PATTERN.matcher(answer);
        while (matcher.find())
        {
            int index = Integer.parseInt(matcher.group(1));
            if (index <= 0 || index > sourceCount)
                throw new IllegalStateException("LLM回答包含无效来源编号S" + index + "，已拒绝返回");
            if (!indexes.contains(index)) indexes.add(index);
        }
        return indexes;
    }

    private String abbreviate(String value, int max)
    {
        if (value == null) return "";
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }
}
