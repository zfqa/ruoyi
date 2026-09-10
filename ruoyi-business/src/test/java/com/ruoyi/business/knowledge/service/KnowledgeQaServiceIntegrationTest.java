package com.ruoyi.business.knowledge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import com.ruoyi.business.knowledge.domain.KnowledgeChunk;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** 从检索结果、Mock Ark响应到逐句证据返回的问答链路集成测试。 */
class KnowledgeQaServiceIntegrationTest
{
    private HttpServer arkServer;
    private String apiUrl;
    private final AtomicReference<String> lastRequestBody = new AtomicReference<>("");
    private final AtomicReference<String> nextAnswer = new AtomicReference<>(
        "Tianma 2025年前三季度LTPS出货量为1200 Kpcs，同比增长20%。[S1]");

    @BeforeEach
    void startMockArk() throws IOException
    {
        arkServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        arkServer.createContext("/chat/completions", exchange -> {
            lastRequestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String escaped = nextAnswer.get().replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\r", "\\r").replace("\n", "\\n");
            String response = "{\"choices\":[{\"message\":{\"content\":\"" + escaped + "\"}}]}";
            byte[] body = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        arkServer.start();
        apiUrl = "http://127.0.0.1:" + arkServer.getAddress().getPort() + "/chat/completions";
    }

    @AfterEach
    void stopMockArk()
    {
        if (arkServer != null) arkServer.stop(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsAnswerClaimsAndTraceableEvidence() throws Exception
    {
        KnowledgeIngestService ingest = mock(KnowledgeIngestService.class);
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(101L); chunk.setSourceId(10L); chunk.setVersionId(20L); chunk.setChunkNo(3);
        chunk.setContent("Tianma 2025年前三季度LTPS出货量为1200 Kpcs，同比增长20%。");
        chunk.setSourceSnippet(chunk.getContent()); chunk.setSourceName("竞争社洞察.pdf"); chunk.setSourceType("PDF");
        chunk.setOriginalName("【终稿】竞争社洞察 Tianma、AUO、CSOT、BOE.pdf");
        chunk.setVersionNo("v1"); chunk.setPageStart(8); chunk.setPageEnd(8);
        when(ingest.search(anyString(), eq("PDF"), anyList(), anyBoolean(), anyInt())).thenReturn(List.of(chunk));

        KnowledgeQaService service = new KnowledgeQaService(ingest,
            new LlmRuntimeConfiguration(apiUrl, "mock-ark", "test-key"));
        Map<String, Object> result = service.ask("Tianma LTPS出货情况", null, List.of(2L), false);

        assertEquals("mock-ark", result.get("model"));
        assertEquals("LLM_VERIFIED", result.get("answerMode"));
        assertTrue(String.valueOf(result.get("answer")).contains("[S1]"));
        List<Map<String, Object>> claims = (List<Map<String, Object>>) result.get("claims");
        List<Map<String, Object>> citations = (List<Map<String, Object>>) result.get("citations");
        assertEquals(1, claims.size());
        assertEquals(true, claims.get(0).get("verified"));
        assertEquals("S1", citations.get(0).get("citationLabel"));
        assertEquals("【终稿】竞争社洞察 Tianma、AUO、CSOT、BOE.pdf", citations.get(0).get("originalName"));
        assertEquals(8, citations.get(0).get("pageStart"));
        assertEquals(1, ((List<?>) citations.get(0).get("evidenceLocations")).size());
        assertTrue(((List<?>) result.get("queryPlan")).size() >= 2);
        assertTrue(((List<?>) result.get("retrievalLogs")).size() >= 2);
        assertTrue(result.containsKey("graph"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void retrievesReportBeforeNewsAndTellsLlmToUseNewsOnlyAsExplanation() throws Exception
    {
        KnowledgeIngestService ingest = mock(KnowledgeIngestService.class);
        KnowledgeChunk report = new KnowledgeChunk();
        report.setId(301L); report.setSourceType("REPORT"); report.setSourceName("结构化分析报告");
        report.setVersionNo("report-v1");
        report.setContent("Tianma 2025年前三季度LTPS出货量为1200 Kpcs，同比增长20%。");
        report.setSourceSnippet(report.getContent());
        KnowledgeChunk news = new KnowledgeChunk();
        news.setId(302L); news.setSourceType("NEWS"); news.setSourceName("行业新闻"); news.setVersionNo("news-v1");
        news.setContent("Tianma发布新一代LTPS车载显示产品，并获得多个车型项目定点。");
        news.setSourceSnippet(news.getContent()); news.setSourceUrl("https://example.com/news/302");
        when(ingest.searchMetrics(anyString(), anyList(), anyBoolean(), anyInt())).thenReturn(List.of(report));
        when(ingest.search(anyString(), eq("REPORT"), anyList(), anyBoolean(), anyInt())).thenReturn(List.of(report));
        when(ingest.search(anyString(), eq("NEWS"), anyList(), anyBoolean(), anyInt())).thenReturn(List.of(news));
        when(ingest.search(anyString(), eq("PDF"), anyList(), anyBoolean(), anyInt())).thenReturn(List.of());

        KnowledgeQaService service = new KnowledgeQaService(ingest,
            new LlmRuntimeConfiguration(apiUrl, "mock-ark", "test-key"));
        Map<String, Object> result = service.ask("解释Tianma LTPS出货增长", null, List.of(2L), false, true);

        Map<String, Integer> breakdown = (Map<String, Integer>) result.get("sourceBreakdown");
        assertEquals(1, breakdown.get("REPORT"));
        assertEquals(1, breakdown.get("NEWS"));
        assertEquals(true, result.get("newsExplanationEnabled"));
        assertTrue(lastRequestBody.get().contains("[分析结果证据]"));
        assertTrue(lastRequestBody.get().contains("[新闻解释证据]"));
        assertTrue(lastRequestBody.get().contains("新闻只能解释"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void combinesStructuredDataAndApplicablePolicyAsCautiousTraceableExplanation() throws Exception
    {
        KnowledgeIngestService ingest = mock(KnowledgeIngestService.class);
        KnowledgeChunk report = new KnowledgeChunk();
        report.setId(601L); report.setSourceType("REPORT"); report.setSourceName("新能源汽车销量报告");
        report.setVersionNo("report-v1");
        report.setContent("2025年新能源汽车销量同比增长20%。"); report.setSourceSnippet(report.getContent());
        KnowledgeChunk policy = new KnowledgeChunk();
        policy.setId(602L); policy.setSourceType("POLICY"); policy.setSourceName("汽车以旧换新政策");
        policy.setVersionNo("policy-v1"); policy.setSourceUrl("https://www.gov.cn/zhengce/example.html");
        policy.setContent("政策名称：汽车以旧换新政策。政策支持新能源汽车消费，改善汽车消费需求环境。");
        policy.setSourceSnippet(policy.getContent());
        when(ingest.searchMetrics(anyString(), anyList(), anyBoolean(), anyInt())).thenReturn(List.of(report));
        when(ingest.search(anyString(), eq("REPORT"), anyList(), anyBoolean(), anyInt())).thenReturn(List.of(report));
        when(ingest.search(anyString(), eq("NEWS"), anyList(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(ingest.search(anyString(), eq("POLICY"), anyList(), anyBoolean(), anyInt())).thenReturn(List.of(policy));
        when(ingest.search(anyString(), eq("PDF"), anyList(), anyBoolean(), anyInt())).thenReturn(List.of());
        nextAnswer.set("数据结论\n2025年新能源汽车销量同比增长20%。[S1]\n"
            + "外部解释\n新能源汽车销量增长可能与汽车以旧换新政策改善消费环境有关。[S1][S2]");

        KnowledgeQaService service = new KnowledgeQaService(ingest,
            new LlmRuntimeConfiguration(apiUrl, "mock-ark", "test-key"));
        Map<String, Object> result = service.ask("2025年新能源汽车销量增长是否可能受以旧换新政策影响", null,
            List.of(2L), false, true);

        assertEquals("LLM_VERIFIED", result.get("answerMode"));
        Map<String, Integer> breakdown = (Map<String, Integer>) result.get("sourceBreakdown");
        assertEquals(1, breakdown.get("REPORT"));
        assertEquals(1, breakdown.get("POLICY"));
        assertTrue(String.valueOf(result.get("answer")).contains("可能"));
        assertTrue(String.valueOf(result.get("answer")).contains("[S1][S2]"));
        assertTrue(lastRequestBody.get().contains("[政策解释证据]"));
        assertTrue(lastRequestBody.get().contains("禁止写成确定因果或唯一原因"));
        assertTrue(((List<Map<String, Object>>) result.get("retrievalLogs")).stream()
            .anyMatch(row -> "POLICY_SEARCH".equals(row.get("type"))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void retrievesIndustryPolicyWithoutRequiringCompanyNameAndBuildsAuditableTrace() throws Exception
    {
        KnowledgeIngestService ingest = mock(KnowledgeIngestService.class);
        KnowledgeChunk report = new KnowledgeChunk();
        report.setId(701L); report.setSourceType("REPORT"); report.setSourceName("Tianma结构化报告");
        report.setVersionNo("report-v1"); report.setMetricId("tianma.technology.ltps.shipment");
        report.setContent("{\"metric_id\":\"tianma.technology.ltps.shipment\",\"unit\":\"thousand_units\","
            + "\"periods\":{\"Y25Q1-Q3\":10323},\"yoy_periods\":{\"Y25Q1-Q3\":0.680176}}");
        report.setSourceSnippet(report.getContent());
        KnowledgeChunk policy = new KnowledgeChunk();
        policy.setId(702L); policy.setSourceType("POLICY"); policy.setSourceName("车载显示产业政策");
        policy.setVersionNo("policy-v1");
        policy.setContent("政策支持汽车及车载显示制造企业开展技术改造，支持智能座舱产业发展。");
        policy.setSourceSnippet(policy.getContent()); policy.setSourceUrl("https://example.com/policy/702");
        when(ingest.searchMetrics(anyString(), anyList(), anyBoolean(), anyInt())).thenReturn(List.of(report));
        when(ingest.search(anyString(), eq("REPORT"), anyList(), anyBoolean(), anyInt())).thenReturn(List.of(report));
        when(ingest.search(anyString(), eq("NEWS"), anyList(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(ingest.search(anyString(), eq("PDF"), anyList(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(ingest.search(anyString(), eq("POLICY"), anyList(), anyBoolean(), anyInt())).thenAnswer(invocation -> {
            String query = invocation.getArgument(0);
            assertFalse(query.contains("Tianma"));
            assertTrue(query.contains("车载显示"));
            return List.of(policy);
        });

        KnowledgeQaService service = new KnowledgeQaService(ingest,
            new LlmRuntimeConfiguration(apiUrl, "mock-ark", ""));
        Map<String, Object> result = service.ask(
            "分析Tianma 2025年前三季度LTPS出货表现，并结合车载显示政策解释可能影响",
            null, List.of(2L), false, true);

        assertEquals("EXTRACTIVE_FALLBACK", result.get("answerMode"));
        assertEquals(100, result.get("citationCoveragePercent"));
        assertEquals("STRICT_VERIFIED_ONLY", result.get("citationPolicy"));
        assertTrue(String.valueOf(result.get("answer")).contains("10,323 Kpcs（千片）"));
        assertTrue(String.valueOf(result.get("answer")).contains("同比增长68.0%"));
        assertEquals(1, ((Map<String, Integer>) result.get("sourceBreakdown")).get("POLICY"));
        List<Map<String, Object>> trace = (List<Map<String, Object>>) result.get("analysisTrace");
        assertEquals(5, trace.size());
        assertTrue(String.valueOf(trace.get(0).get("action")).contains("我需要先从结构化报告确认数值"));
        assertTrue(((List<Map<String, Object>>) trace.get(2).get("evidences")).stream()
            .anyMatch(e -> e.get("chunkId").equals(702L) && ((Number)e.get("endOffset")).intValue() > 0));
        assertTrue(String.valueOf(trace.get(3).get("boundary")).contains("不等于企业增长由政策导致"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsVerifiedExtractiveEvidenceWhenApiKeyIsMissing() throws Exception
    {
        KnowledgeIngestService ingest = mock(KnowledgeIngestService.class);
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(201L); chunk.setSourceId(10L); chunk.setVersionId(20L); chunk.setChunkNo(1);
        chunk.setContent("Tianma 2025年前三季度LTPS出货量为1200 Kpcs。第二句不应共用引用。");
        chunk.setSourceSnippet(chunk.getContent()); chunk.setSourceName("结构化报告"); chunk.setSourceType("REPORT");
        chunk.setOriginalName("report.json"); chunk.setVersionNo("v1");
        when(ingest.searchMetrics(anyString(), anyList(), anyBoolean(), anyInt())).thenReturn(List.of(chunk));
        when(ingest.search(anyString(), isNull(), anyList(), anyBoolean(), anyInt())).thenReturn(List.of(chunk));

        KnowledgeQaService service = new KnowledgeQaService(ingest,
            new LlmRuntimeConfiguration(apiUrl, "mock-ark", ""));
        Map<String, Object> result = service.ask("Tianma LTPS出货量是多少", null, List.of(2L), false);

        assertEquals("EXTRACTIVE_FALLBACK", result.get("answerMode"));
        assertTrue(String.valueOf(result.get("answer")).contains("1200 Kpcs。 [S1]"));
        assertEquals(1, ((List<Map<String, Object>>) result.get("claims")).size());
        assertEquals(2, ((List<?>) result.get("warnings")).size());
    }

    @Test
    @SuppressWarnings("unchecked")
    void extractiveFallbackKeepsNewsEvidenceAndItsOriginalUrl() throws Exception
    {
        KnowledgeIngestService ingest = mock(KnowledgeIngestService.class);
        KnowledgeChunk report = new KnowledgeChunk();
        report.setId(401L); report.setSourceType("REPORT"); report.setSourceName("整车分析结果");
        report.setVersionNo("report-v1"); report.setContent("小鹏Y25前三季度交付量指标已完成确定性计算。");
        report.setSourceSnippet(report.getContent());
        KnowledgeChunk news = new KnowledgeChunk();
        news.setId(402L); news.setSourceType("NEWS"); news.setSourceName("小鹏汽车新闻");
        news.setVersionNo("news-v1"); news.setContent("小鹏汽车发布新车型并披露近期交付进展。");
        news.setSourceSnippet(news.getContent()); news.setSourceUrl("https://example.com/xpeng/402");
        when(ingest.search(anyString(), eq("REPORT"), anyList(), anyBoolean(), anyInt())).thenReturn(List.of(report));
        when(ingest.search(anyString(), eq("NEWS"), anyList(), anyBoolean(), anyInt())).thenReturn(List.of(news));
        when(ingest.search(anyString(), eq("PDF"), anyList(), anyBoolean(), anyInt())).thenReturn(List.of());

        KnowledgeQaService service = new KnowledgeQaService(ingest,
            new LlmRuntimeConfiguration(apiUrl, "mock-ark", ""));
        Map<String, Object> result = service.ask("小鹏近期交付与市场动作如何", null, List.of(2L), false, true);

        String answer = String.valueOf(result.get("answer"));
        assertTrue(answer.contains("数据与报告证据"));
        assertTrue(answer.contains("新闻与政策解释证据"));
        List<Map<String, Object>> citations = (List<Map<String, Object>>) result.get("citations");
        assertEquals(2, citations.size());
        assertEquals("NEWS", citations.get(1).get("sourceType"));
        assertEquals("https://example.com/xpeng/402", citations.get(1).get("sourceUrl"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void newsOnlyFallbackPassesStrictCitationValidation() throws Exception
    {
        KnowledgeIngestService ingest = mock(KnowledgeIngestService.class);
        KnowledgeChunk news = new KnowledgeChunk();
        news.setId(501L); news.setSourceType("NEWS"); news.setSourceName("小鹏汽车新闻");
        news.setVersionNo("news-v1"); news.setContent("标题：小鹏集团7月交付成绩\n来源：小鹏汽车\n发布时间：2026/08/03\n正文：小鹏集团7月共交付新车38,027台，同比增长约4%。");
        news.setSourceSnippet(news.getContent()); news.setSourceUrl("https://example.com/xpeng/501");
        when(ingest.search(anyString(), eq("REPORT"), anyList(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(ingest.search(anyString(), eq("NEWS"), anyList(), anyBoolean(), anyInt())).thenReturn(List.of(news));
        when(ingest.search(anyString(), eq("PDF"), anyList(), anyBoolean(), anyInt())).thenReturn(List.of());

        KnowledgeQaService service = new KnowledgeQaService(ingest,
            new LlmRuntimeConfiguration(apiUrl, "mock-ark", ""));
        Map<String, Object> result = service.ask("小鹏近期交付情况", null, List.of(2L), false, true);

        assertEquals("EXTRACTIVE_FALLBACK", result.get("answerMode"));
        assertTrue(String.valueOf(result.get("answer")).contains("现有资料不足以形成结构化数据结论："));
        List<Map<String, Object>> citations = (List<Map<String, Object>>) result.get("citations");
        assertEquals(1, citations.size());
        assertEquals("NEWS", citations.get(0).get("sourceType"));
        assertEquals("https://example.com/xpeng/501", citations.get(0).get("sourceUrl"));
    }

    @Test
    void runtimeConfigurationIsWriteOnlyValidatedAndTestable() throws Exception
    {
        KnowledgeQaService service = new KnowledgeQaService(mock(KnowledgeIngestService.class),
            new LlmRuntimeConfiguration("https://ark.cn-beijing.volces.com/api/v3/chat/completions",
                "initial-model", ""));

        Map<String, Object> initial = service.llmConfiguration();
        assertEquals(false, initial.get("apiKeyConfigured"));
        assertFalse(initial.containsKey("apiKey"));

        Map<String, Object> updated = service.updateLlmConfiguration(apiUrl, "mock-ark", "secret-key", false);
        assertEquals(true, updated.get("apiKeyConfigured"));
        assertEquals("mock-ark", updated.get("model"));
        assertFalse(updated.containsKey("apiKey"));
        assertEquals(true, service.testLlmConfiguration().get("success"));

        service.updateLlmConfiguration("", "", "", true);
        assertEquals(false, service.llmConfiguration().get("apiKeyConfigured"));
        assertThrows(IllegalArgumentException.class, service::testLlmConfiguration);
        assertThrows(IllegalArgumentException.class, () -> service.updateLlmConfiguration(
            "http://example.com/chat/completions", "valid-model", "", false));
        assertThrows(IllegalArgumentException.class, () -> service.updateLlmConfiguration(
            "https://example.com/chat/completions", "invalid model name", "", false));
    }

    @Test
    void previewPlanDynamicallyShowsEntityTimeAndMultipleRetrievalTasks()
    {
        KnowledgeQaService service = new KnowledgeQaService(mock(KnowledgeIngestService.class),
            new LlmRuntimeConfiguration(apiUrl, "mock-ark", ""));

        List<Map<String, Object>> plan = service.previewQueryPlan(
            "分析小鹏2026 Q3销量增长，并结合新闻解释原因", "", true);

        assertTrue(plan.size() >= 4);
        assertTrue(String.valueOf(plan.get(0).get("input")).contains("XPeng"));
        assertTrue(String.valueOf(plan.get(0).get("input")).contains("2026 Q3"));
        assertTrue(String.valueOf(plan.get(0).get("input")).contains("数据结论+新闻/政策解释"));
        assertTrue(plan.stream().anyMatch(step -> "查询新闻与政策".equals(step.get("name"))));
    }
}
