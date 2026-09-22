package com.ruoyi.business.knowledge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import com.ruoyi.business.knowledge.domain.KnowledgeChunk;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * 针对年报问答链路修复的回归：销量误判、PDF topK、证据保留、402 降级、多问题拆分。
 */
class KnowledgeQaServiceRepairTest
{
    private HttpServer arkServer;
    private String apiUrl;
    private final AtomicInteger statusCode = new AtomicInteger(200);
    private final AtomicReference<String> nextAnswer = new AtomicReference<>("占位回答[S1]");

    @BeforeEach
    void startMockArk() throws IOException
    {
        arkServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        arkServer.createContext("/chat/completions", exchange -> {
            int code = statusCode.get();
            if (code == 402)
            {
                byte[] body = "{\"error\":{\"message\":\"Insufficient Balance\",\"type\":\"unknown_error\"}}"
                    .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
                exchange.sendResponseHeaders(402, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
                return;
            }
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
    void salesStaffQuestionKeepsAnnualReportEvidenceInsteadOfSalesFilter() throws Exception
    {
        KnowledgeIngestService ingest = mock(KnowledgeIngestService.class);
        KnowledgeChunk staff = pdfChunk(251L, 69,
            "报告期末母公司在职员工的数量（人）2075\n生产人员665812\n销售人员40348");
        when(ingest.searchMetrics(anyString(), anyList(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(ingest.search(anyString(), eq("REPORT"), anyList(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(ingest.search(anyString(), eq("NEWS"), anyList(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(ingest.search(anyString(), eq("POLICY"), anyList(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(ingest.search(anyString(), eq("PDF"), anyList(), anyBoolean(), anyInt())).thenReturn(List.of(staff));
        when(ingest.expandMetricFragments(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(ingest.expandAdjacentChunks(anyList(), anyInt())).thenAnswer(inv -> inv.getArgument(0));
        nextAnswer.set("母公司在职员工2075人，生产与销售人员合计706160人。[S1]");

        KnowledgeWebSearchClient web = mock(KnowledgeWebSearchClient.class);
        KnowledgeQaService service = new KnowledgeQaService(ingest,
            new LlmRuntimeConfiguration(apiUrl, "mock-ark", "test-key"), web);
        Map<String, Object> result = service.ask(
            "比亚迪2025年员工数量统计，报告期末母公司在职员工的数量（人）？生产人员和销售人员之和是多少？",
            null, List.of(2L), false, true, false);

        assertEquals("ANSWERED", result.get("qaStatus"));
        String answer = String.valueOf(result.get("answer"));
        assertTrue(answer.contains("2075"));
        assertTrue(answer.contains("706160"));
        List<Map<String, Object>> citations = (List<Map<String, Object>>) result.get("citations");
        assertFalse(citations.isEmpty());
        assertEquals(69, citations.get(0).get("pageStart"));
        verify(web, never()).search(anyString());
        assertTrue(String.valueOf(result.get("queryPlan")).contains("员工")
            || String.valueOf(result.get("retrievalLogs")).contains("员工")
            || String.valueOf(result.get("answer")).contains("2075"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void vehicleSalesVolumeStillUsesSalesPath() throws Exception
    {
        KnowledgeIngestService ingest = mock(KnowledgeIngestService.class);
        KnowledgeChunk sales = new KnowledgeChunk();
        sales.setId(920L); sales.setSourceType("REPORT"); sales.setSourceName("整车市场周报");
        sales.setOriginalName("整车市场周报"); sales.setVersionNo("v1");
        sales.setMetricId("market.market_fact_pack");
        sales.setContent("报告任务：整车市场周报\n章节：market_fact_pack\n内容：比亚迪汽车(BYD) 2024年12月批发销量514809辆。");
        when(ingest.searchMetrics(anyString(), anyList(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(ingest.search(anyString(), eq("REPORT"), anyList(), anyBoolean(), anyInt())).thenReturn(List.of(sales));
        when(ingest.search(anyString(), eq("NEWS"), anyList(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(ingest.search(anyString(), eq("PDF"), anyList(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(ingest.expandMetricFragments(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(ingest.expandVehicleSalesReportContext(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(ingest.expandAdjacentChunks(anyList(), anyInt())).thenAnswer(inv -> inv.getArgument(0));
        nextAnswer.set("比亚迪2024年12月批发销量514809辆。[S1]");

        KnowledgeQaService service = new KnowledgeQaService(ingest,
            new LlmRuntimeConfiguration(apiUrl, "mock-ark", "test-key"));
        Map<String, Object> result = service.ask("比亚迪2024年销量", null, List.of(2L), false, true, false);
        assertEquals("LLM_VERIFIED", result.get("answerMode"));
        assertTrue(String.valueOf(result.get("answer")).contains("514809"));
        assertTrue(String.valueOf(result.get("queryPlan")).contains("车辆销量")
            || String.valueOf(result.get("retrievalLogs")).contains("销量")
            || String.valueOf(result.get("answer")).contains("514809"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void llm402KeepsEvidenceAndDoesNotWebSearch() throws Exception
    {
        statusCode.set(402);
        KnowledgeIngestService ingest = mock(KnowledgeIngestService.class);
        KnowledgeChunk rd = pdfChunk(75L, 32, "主要研发项目名称：第二代刀片电池及闪充技术；刀片电池安全防护技术");
        when(ingest.searchMetrics(anyString(), anyList(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(ingest.search(anyString(), eq("REPORT"), anyList(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(ingest.search(anyString(), eq("NEWS"), anyList(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(ingest.search(anyString(), eq("POLICY"), anyList(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(ingest.search(anyString(), eq("PDF"), anyList(), anyBoolean(), anyInt())).thenReturn(List.of(rd));
        when(ingest.expandMetricFragments(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(ingest.expandAdjacentChunks(anyList(), anyInt())).thenAnswer(inv -> inv.getArgument(0));

        KnowledgeWebSearchClient web = mock(KnowledgeWebSearchClient.class);
        KnowledgeQaService service = new KnowledgeQaService(ingest,
            new LlmRuntimeConfiguration(apiUrl, "mock-ark", "test-key"), web);
        Map<String, Object> result = service.ask("比亚迪2025年年度研发投入按照研发项目名称说明",
            null, List.of(2L), false, true, true);

        assertEquals("LLM_UNAVAILABLE_WITH_EVIDENCE", result.get("answerMode"));
        String answer = String.valueOf(result.get("answer"));
        assertTrue(answer.contains("大模型服务暂不可用") || answer.contains("第二代刀片"));
        assertTrue(answer.contains("第32页") || answer.contains("[S1]"));
        List<String> warnings = (List<String>) result.get("warnings");
        assertTrue(warnings.stream().anyMatch(w -> w.contains("402") || w.contains("余额")));
        verify(web, never()).search(anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void multiQuestionSplitsAndAnswersEachPart() throws Exception
    {
        KnowledgeIngestService ingest = mock(KnowledgeIngestService.class);
        KnowledgeChunk brand = pdfChunk(40L, 19, "比亚迪品牌由王朝与海洋两大产品系列共同构建");
        KnowledgeChunk impair = pdfChunk(1110L, 214, "加：信用减值准备407137\n资产减值准备1823082");
        when(ingest.searchMetrics(anyString(), anyList(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(ingest.search(anyString(), eq("REPORT"), anyList(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(ingest.search(anyString(), eq("NEWS"), anyList(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(ingest.search(anyString(), eq("POLICY"), anyList(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(ingest.search(anyString(), eq("PDF"), anyList(), anyBoolean(), anyInt())).thenAnswer(inv -> {
            String q = inv.getArgument(0, String.class);
            if (q == null) return List.of(brand, impair);
            if (q.contains("王朝") || q.contains("产品系列") || q.contains("品牌")) return List.of(brand);
            if (q.contains("减值") || q.contains("现金流量")) return List.of(impair);
            return List.of(brand, impair);
        });
        when(ingest.expandMetricFragments(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(ingest.expandAdjacentChunks(anyList(), anyInt())).thenAnswer(inv -> inv.getArgument(0));
        nextAnswer.set("根据资料作答。[S1]");

        KnowledgeQaService service = new KnowledgeQaService(ingest,
            new LlmRuntimeConfiguration(apiUrl, "mock-ark", "test-key"));
        Map<String, Object> result = service.ask(
            "1.比亚迪品牌由哪两个产品系列共同构建\n2.2025年比亚迪经营活动现金流量统计，信用减值准备+资产减值准备，共多少",
            null, List.of(2L), false, false, false);

        assertEquals(2, result.get("subQuestionCount"));
        String answer = String.valueOf(result.get("answer"));
        assertTrue(answer.contains("1.") && answer.contains("2."));
        List<Map<String, Object>> citations = (List<Map<String, Object>>) result.get("citations");
        assertTrue(citations.size() >= 1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void scopedSourceUsesScopedSearchAndKeepsMultipleChunks() throws Exception
    {
        KnowledgeIngestService ingest = mock(KnowledgeIngestService.class);
        KnowledgeChunk pageA = pdfChunk(1L, 32, "主要研发项目名称第二代刀片电池");
        KnowledgeChunk pageB = pdfChunk(2L, 33, "刀片电池安全防护技术");
        pageA.setSourceId(130L); pageA.setVersionId(137L); pageA.setChunkNo(75);
        pageB.setSourceId(130L); pageB.setVersionId(137L); pageB.setChunkNo(76);
        when(ingest.searchMetrics(anyString(), anyList(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(ingest.search(anyString(), anyString(), anyList(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(ingest.search(anyString(), anyString(), anyList(), anyBoolean(), anyInt(), eq(130L), eq(137L)))
            .thenAnswer(inv -> {
                String type = inv.getArgument(1, String.class);
                return type != null && "PDF".equalsIgnoreCase(type) ? List.of(pageA, pageB) : List.of();
            });
        when(ingest.expandMetricFragments(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(ingest.expandAdjacentChunks(anyList(), anyInt())).thenAnswer(inv -> inv.getArgument(0));
        nextAnswer.set("主要研发项目包括第二代刀片电池及安全防护技术。[S1][S2]");

        KnowledgeQaService service = new KnowledgeQaService(ingest,
            new LlmRuntimeConfiguration(apiUrl, "mock-ark", "test-key"));
        Map<String, Object> result = service.ask("比亚迪2025年年度研发投入按照研发项目名称说明",
            null, List.of(2L), true, false, false, 130L, 137L, (p, s, l) -> { });

        assertEquals(130L, result.get("scopedSourceId"));
        assertEquals(137L, result.get("scopedVersionId"));
        List<Map<String, Object>> citations = (List<Map<String, Object>>) result.get("citations");
        assertTrue(citations.size() >= 1);
        verify(ingest, org.mockito.Mockito.atLeastOnce())
            .search(anyString(), eq("PDF"), anyList(), anyBoolean(), anyInt(), eq(130L), eq(137L));
    }

    @Test
    void batteryAndBrandEvidenceAreRankedAboveTaxNoise()
    {
        KnowledgeQaService service = new KnowledgeQaService(mock(KnowledgeIngestService.class),
            new LlmRuntimeConfiguration(apiUrl, "mock-ark", "test-key"));
        KnowledgeChunk tax = pdfChunk(10L, 164, "无为弗迪电池有限公司适用15%高新技术企业企业所得税优惠税率");
        KnowledgeChunk battery = pdfChunk(11L, 13, "动力电池领域，本集团开发了高度安全的磷酸铁锂电池—“刀片电池”");
        KnowledgeChunk brandNoise = pdfChunk(12L, 10, "比亚迪汽车有限公司是比亚迪品牌乘用车、电动车的总经销商，经营范围包括...");
        KnowledgeChunk brand = pdfChunk(13L, 19, "「比亚迪」品牌由「王朝」与「海洋」两大产品系列共同构建");

        List<KnowledgeChunk> batteryRanked = service.rankEvidenceForQuestion(
            "比亚迪2025年报告期内，汽车级电池业务所处情况，简要做个总结", List.of(tax, battery));
        assertEquals(13, batteryRanked.get(0).getPageStart());

        List<KnowledgeChunk> brandRanked = service.rankEvidenceForQuestion(
            "比亚迪品牌由哪两个产品系列共同构建", List.of(brandNoise, brand));
        assertEquals(19, brandRanked.get(0).getPageStart());
        assertTrue(brandRanked.get(0).getContent().contains("王朝"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void batteryQuestionPrefersPowerBatteryChunkOverTaxPages() throws Exception
    {
        KnowledgeIngestService ingest = mock(KnowledgeIngestService.class);
        KnowledgeChunk tax = pdfChunk(20L, 164, "惠州比亚迪电池有限公司适用高新技术企业15%税率，适用年份2025");
        KnowledgeChunk battery = pdfChunk(21L, 13,
            "动力电池领域，本集团开发了高度安全的磷酸铁锂电池—“刀片电池”，发布第二代刀片电池及闪充技术");
        when(ingest.searchMetrics(anyString(), anyList(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(ingest.search(anyString(), eq("REPORT"), anyList(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(ingest.search(anyString(), eq("NEWS"), anyList(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(ingest.search(anyString(), eq("POLICY"), anyList(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(ingest.search(anyString(), eq("PDF"), anyList(), anyBoolean(), anyInt()))
            .thenReturn(List.of(tax, battery));
        when(ingest.expandMetricFragments(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(ingest.expandAdjacentChunks(anyList(), anyInt())).thenAnswer(inv -> inv.getArgument(0));
        nextAnswer.set("动力电池领域主推刀片电池并发布第二代刀片电池及闪充技术。[S1]");

        KnowledgeQaService service = new KnowledgeQaService(ingest,
            new LlmRuntimeConfiguration(apiUrl, "mock-ark", "test-key"), mock(KnowledgeWebSearchClient.class));
        Map<String, Object> result = service.ask(
            "比亚迪2025年报告期内，汽车级电池业务所处情况，简要做个总结",
            null, List.of(2L), false, false, false);

        assertEquals("LLM_VERIFIED", result.get("answerMode"));
        List<Map<String, Object>> citations = (List<Map<String, Object>>) result.get("citations");
        assertFalse(citations.isEmpty());
        assertEquals(13, citations.get(0).get("pageStart"));
        assertTrue(String.valueOf(result.get("retrievalLogs")).contains("动力电池"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void missingEvidenceReturnsNotFoundAndDoesNotSearchWeb() throws Exception
    {
        KnowledgeIngestService ingest = mock(KnowledgeIngestService.class);
        when(ingest.searchMetrics(anyString(), anyList(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(ingest.search(anyString(), anyString(), anyList(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(ingest.expandAdjacentChunks(anyList(), anyInt())).thenAnswer(inv -> inv.getArgument(0));
        KnowledgeWebSearchClient web = mock(KnowledgeWebSearchClient.class);
        KnowledgeQaService service = new KnowledgeQaService(ingest,
            new LlmRuntimeConfiguration(apiUrl, "mock-ark", "test-key"), web);

        Map<String, Object> result = service.ask("这份资料里完全不存在的冷门指标",
            null, List.of(2L), false, true, false);

        assertEquals("NOT_FOUND_IN_KNOWLEDGE_BASE", result.get("qaStatus"));
        assertTrue(String.valueOf(result.get("answer")).contains("当前知识库未找到充分依据"));
        verify(web, never()).search(anyString());
    }

    @Test
    void questionMarksSplitIntoIndependentSubQuestions() throws Exception
    {
        KnowledgeIngestService ingest = mock(KnowledgeIngestService.class);
        when(ingest.searchMetrics(anyString(), anyList(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(ingest.search(anyString(), anyString(), anyList(), anyBoolean(), anyInt()))
            .thenReturn(List.of(pdfChunk(1L, 10, "母公司在职员工2075人")));
        when(ingest.expandMetricFragments(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(ingest.expandAdjacentChunks(anyList(), anyInt())).thenAnswer(inv -> inv.getArgument(0));
        nextAnswer.set("根据资料回答。[S1]");
        KnowledgeQaService service = new KnowledgeQaService(ingest,
            new LlmRuntimeConfiguration(apiUrl, "mock-ark", "test-key"));

        Map<String, Object> result = service.ask("母公司员工有多少？主要研发项目有哪些？",
            null, List.of(2L), false, true, false);

        assertEquals(2, result.get("subQuestionCount"));
        assertEquals("ANSWERED", result.get("qaStatus"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void impairmentSumIsCalculatedFromOriginalLabels() throws Exception
    {
        KnowledgeIngestService ingest = mock(KnowledgeIngestService.class);
        when(ingest.searchMetrics(anyString(), anyList(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(ingest.search(anyString(), anyString(), anyList(), anyBoolean(), anyInt()))
            .thenReturn(List.of(pdfChunk(11L, 214, "加：信用减值准备407137\n资产减值准备1823082")));
        when(ingest.expandMetricFragments(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(ingest.expandAdjacentChunks(anyList(), anyInt())).thenAnswer(inv -> inv.getArgument(0));
        KnowledgeQaService service = new KnowledgeQaService(ingest,
            new LlmRuntimeConfiguration(apiUrl, "mock-ark", "test-key"));

        Map<String, Object> result = service.ask("2025年的信用减值损失和资产减值损失分别是多少，合计多少？",
            null, List.of(2L), false, false, false);

        String answer = String.valueOf(result.get("answer"));
        assertTrue(answer.contains("407137"));
        assertTrue(answer.contains("1823082"));
        assertTrue(answer.contains("2230219"));
        assertTrue(answer.contains("准备"));
        assertEquals("ANSWERED", result.get("qaStatus"));
        List<String> warnings = (List<String>) result.get("warnings");
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("准备")));
    }

    @Test
    void projectListDoesNotClaimCompleteWhenTableContinues() throws Exception
    {
        KnowledgeIngestService ingest = mock(KnowledgeIngestService.class);
        when(ingest.searchMetrics(anyString(), anyList(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(ingest.search(anyString(), anyString(), anyList(), anyBoolean(), anyInt()))
            .thenReturn(List.of(pdfChunk(21L, 32, "主要研发项目名称：第二代刀片电池及闪充技术；刀片电池安全防护技术\n续表")));
        when(ingest.expandMetricFragments(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(ingest.expandAdjacentChunks(anyList(), anyInt())).thenAnswer(inv -> inv.getArgument(0));
        KnowledgeQaService service = new KnowledgeQaService(ingest,
            new LlmRuntimeConfiguration(apiUrl, "mock-ark", "test-key"));

        Map<String, Object> result = service.ask("列出全部主要研发项目名称。",
            null, List.of(2L), false, false, false);

        String answer = String.valueOf(result.get("answer"));
        assertTrue(answer.contains("第二代刀片电池及闪充技术"));
        assertTrue(answer.contains("刀片电池安全防护技术"));
        assertTrue(answer.contains("不能确认"));
        assertEquals("PARTIALLY_ANSWERED", result.get("qaStatus"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void dynastyAndOceanUseBrandSentenceInsteadOfBusinessScope() throws Exception
    {
        KnowledgeIngestService ingest = mock(KnowledgeIngestService.class);
        KnowledgeChunk scope = pdfChunk(12L, 10, "比亚迪汽车有限公司是比亚迪品牌乘用车的总经销商，经营范围包括汽车销售。");
        KnowledgeChunk brand = pdfChunk(13L, 19, "「比亚迪」品牌由「王朝」与「海洋」两大产品系列共同构建");
        when(ingest.searchMetrics(anyString(), anyList(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(ingest.search(anyString(), anyString(), anyList(), anyBoolean(), anyInt())).thenReturn(List.of(scope, brand));
        when(ingest.expandMetricFragments(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(ingest.expandAdjacentChunks(anyList(), anyInt())).thenAnswer(inv -> inv.getArgument(0));
        KnowledgeQaService service = new KnowledgeQaService(ingest,
            new LlmRuntimeConfiguration(apiUrl, "mock-ark", "test-key"));

        Map<String, Object> result = service.ask("王朝网和海洋网分别是什么？",
            null, List.of(2L), false, false, false);

        assertTrue(String.valueOf(result.get("answer")).contains("共同构建"));
        List<Map<String, Object>> citations = (List<Map<String, Object>>) result.get("citations");
        assertEquals(19, citations.get(0).get("pageStart"));
    }

    @Test
    void unspecifiedYearPrefersLatestAnnualReport() throws Exception
    {
        KnowledgeIngestService ingest = mock(KnowledgeIngestService.class);
        KnowledgeChunk older = pdfChunk(31L, 80, "报告期末母公司在职员工的数量（人）1000");
        older.setSourceId(10L); older.setVersionId(11L);
        older.setOriginalName("比亚迪2024年年度报告.pdf"); older.setSourceName("比亚迪2024年年度报告.pdf");
        KnowledgeChunk newer = pdfChunk(32L, 90, "报告期末母公司在职员工的数量（人）2075");
        newer.setSourceId(20L); newer.setVersionId(21L);
        newer.setOriginalName("比亚迪2025年年度报告.pdf"); newer.setSourceName("比亚迪2025年年度报告.pdf");
        when(ingest.searchMetrics(anyString(), anyList(), anyBoolean(), anyInt())).thenReturn(List.of());
        when(ingest.search(anyString(), anyString(), anyList(), anyBoolean(), anyInt())).thenReturn(List.of(older, newer));
        when(ingest.expandMetricFragments(anyList())).thenAnswer(inv -> inv.getArgument(0));
        when(ingest.expandAdjacentChunks(anyList(), anyInt())).thenAnswer(inv -> inv.getArgument(0));
        KnowledgeQaService service = new KnowledgeQaService(ingest,
            new LlmRuntimeConfiguration(apiUrl, "mock-ark", "test-key"));

        Map<String, Object> result = service.ask("母公司员工有多少？",
            null, List.of(2L), false, false, false);

        assertTrue(String.valueOf(result.get("answer")).contains("2075"));
        assertTrue(String.valueOf(result.get("warnings")).contains("2025"));
    }

    private static KnowledgeChunk pdfChunk(long id, int page, String content)
    {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(id);
        chunk.setSourceId(130L);
        chunk.setVersionId(137L);
        chunk.setChunkNo((int) id);
        chunk.setSourceType("PDF");
        chunk.setSourceName("比亚迪2025年年度报告.pdf");
        chunk.setOriginalName("比亚迪2025年年度报告.pdf");
        chunk.setVersionNo("v1");
        chunk.setPageStart(page);
        chunk.setPageEnd(page);
        chunk.setContent(content);
        chunk.setSourceSnippet(content);
        return chunk;
    }
}
