package com.ruoyi.business.data.text.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.business.knowledge.service.LlmRuntimeConfiguration;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class TextEntityExtractionServiceTest
{
    private HttpServer server;
    private TextEntityExtractionService service;

    @BeforeEach
    void startServer() throws IOException
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String extracted = requestBody.contains("1.25 million vehicles") ? englishMillionResponse()
                : requestBody.contains("267,200 units") ? englishResponse() : chineseResponse();
            JSONObject body = new JSONObject(); JSONObject message = new JSONObject(); message.put("content", extracted);
            JSONObject choice = new JSONObject(); choice.put("message", message); body.put("choices", new JSONArray(java.util.List.of(choice)));
            byte[] bytes = body.toJSONString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/chat/completions";
        service = new TextEntityExtractionService(new LlmRuntimeConfiguration(url, "mock-model", "test-key"));
    }

    private String chineseResponse()
    {
        return "{\"entities\":["
                + "{\"type\":\"COMPANY\",\"role\":\"SUPPLIER\",\"rawValue\":\"Tianma\",\"evidence\":\"Tianma车型A\",\"confidence\":0.98},"
                + "{\"type\":\"VEHICLE_MODEL\",\"rawValue\":\"车型A\",\"evidence\":\"Tianma车型A\",\"confidence\":0.9},"
                + "{\"type\":\"SALES\",\"rawValue\":\"12万辆\",\"evidence\":\"销量12万辆\",\"confidence\":0.95},"
                + "{\"type\":\"SIZE\",\"rawValue\":\"254mm\",\"evidence\":\"尺寸254mm\",\"confidence\":0.88},"
                + "{\"type\":\"TECHNOLOGY\",\"rawValue\":\"A-Si\",\"evidence\":\"采用A-Si技术\",\"confidence\":0.92}],"
                + "\"records\":[{\"supplier\":\"Tianma\",\"customer\":\"\",\"vehicleModel\":\"车型A\","
                + "\"technology\":\"A-Si\",\"size\":\"254mm\",\"sales\":\"12万辆\","
                + "\"evidence\":\"Tianma车型A销量12万辆，尺寸254mm，采用A-Si技术\",\"confidence\":0.96}],\"warnings\":[]}";
    }

    private String englishResponse()
    {
        return "{\"entities\":["
            + "{\"type\":\"COMPANY\",\"role\":\"CUSTOMER\",\"rawValue\":\"Tesla\",\"evidence\":\"Tesla Model Y\",\"confidence\":0.99},"
            + "{\"type\":\"VEHICLE_MODEL\",\"rawValue\":\"Model Y\",\"evidence\":\"Tesla Model Y\",\"confidence\":0.98},"
            + "{\"type\":\"SALES\",\"rawValue\":\"267,200 units\",\"evidence\":\"deliveries reached 267,200 units\",\"confidence\":0.97},"
            + "{\"type\":\"SIZE\",\"rawValue\":\"15-inch\",\"evidence\":\"15-inch central display\",\"confidence\":0.96},"
            + "{\"type\":\"TECHNOLOGY\",\"rawValue\":\"BEV\",\"evidence\":\"BEV powertrain\",\"confidence\":0.97}],"
            + "\"records\":[{\"supplier\":\"\",\"customer\":\"Tesla\",\"vehicleModel\":\"Model Y\","
            + "\"technology\":\"BEV\",\"size\":\"15-inch\",\"sales\":\"267,200 units\","
            + "\"evidence\":\"Tesla Model Y deliveries reached 267,200 units. The vehicle uses a BEV powertrain and a 15-inch central display\",\"confidence\":0.97}],\"warnings\":[]}";
    }

    private String englishMillionResponse()
    {
        return "{\"entities\":["
            + "{\"type\":\"COMPANY\",\"role\":\"CUSTOMER\",\"rawValue\":\"BYD\",\"evidence\":\"BYD sold\",\"confidence\":0.99},"
            + "{\"type\":\"SALES\",\"rawValue\":\"1.25 million vehicles\",\"evidence\":\"1.25 million vehicles\",\"confidence\":0.99},"
            + "{\"type\":\"TECHNOLOGY\",\"rawValue\":\"plug-in hybrid\",\"evidence\":\"plug-in hybrid powertrain\",\"confidence\":0.96}],"
            + "\"records\":[{\"supplier\":\"\",\"customer\":\"BYD\",\"vehicleModel\":\"\","
            + "\"technology\":\"plug-in hybrid\",\"size\":\"\",\"sales\":\"1.25 million vehicles\","
            + "\"evidence\":\"BYD sold 1.25 million vehicles using its plug-in hybrid powertrain\",\"confidence\":0.97}],\"warnings\":[]}";
    }

    @AfterEach void stopServer() { if (server != null) server.stop(0); }

    @Test
    void extractsAndNormalizesIndustryEntities() throws Exception
    {
        JSONObject result = service.extract("Tianma车型A销量12万辆，尺寸254mm，采用A-Si技术。");
        JSONArray entities = result.getJSONArray("entities");
        assertEquals(5, result.getIntValue("entityCount"));
        assertEquals("120000 unit", find(entities, "SALES").getString("normalizedValue"));
        assertEquals("10 inch", find(entities, "SIZE").getString("normalizedValue"));
        assertEquals("a-Si", find(entities, "TECHNOLOGY").getString("normalizedValue"));
        assertTrue(find(entities, "COMPANY").getIntValue("startOffset") >= 0);
        assertEquals("SUPPLIER", find(entities, "COMPANY").getString("role"));
        assertEquals("Tianma", result.getJSONObject("summary").getJSONArray("COMPANY").getString(0));
        assertEquals("text-entity-v2", result.getString("schemaVersion"));
        assertEquals("MIXED", result.getString("detectedLanguage"));
        assertEquals(1, result.getIntValue("recordCount"));
        JSONObject record = result.getJSONArray("records").getJSONObject(0);
        assertEquals("Tianma", record.getString("supplier"));
        assertEquals("车型A", record.getString("vehicleModel"));
        assertEquals("a-Si", record.getString("technology"));
        assertEquals("10 inch", record.getString("size"));
        assertEquals("120000 unit", record.getString("sales"));
        assertTrue(record.getString("evidence").contains("Tianma车型A销量12万辆"));
    }

    @Test
    void extractsEnglishEntitiesAndKeepsTheirRelationship() throws Exception
    {
        String text = "In Q1 2025, Tesla Model Y deliveries reached 267,200 units. "
            + "The vehicle uses a BEV powertrain and a 15-inch central display.";
        JSONObject result = service.extract(text);
        JSONArray entities = result.getJSONArray("entities");
        assertEquals("EN", result.getString("detectedLanguage"));
        assertEquals(5, result.getIntValue("entityCount"));
        assertEquals("267200 unit", find(entities, "SALES").getString("normalizedValue"));
        assertEquals("15 inch", find(entities, "SIZE").getString("normalizedValue"));
        assertEquals("BEV", find(entities, "TECHNOLOGY").getString("normalizedValue"));
        JSONObject record = result.getJSONArray("records").getJSONObject(0);
        assertEquals("Tesla", record.getString("customer"));
        assertEquals("Model Y", record.getString("vehicleModel"));
        assertEquals("267200 unit", record.getString("sales"));
        assertTrue(record.getString("evidence").contains("BEV powertrain"));
    }

    @Test
    void normalizesEnglishMillionAndWrittenTechnology() throws Exception
    {
        JSONObject result = service.extract("BYD sold 1.25 million vehicles using its plug-in hybrid powertrain.");
        assertEquals("1250000 unit", find(result.getJSONArray("entities"), "SALES").getString("normalizedValue"));
        assertEquals("PHEV", find(result.getJSONArray("entities"), "TECHNOLOGY").getString("normalizedValue"));
        assertEquals("PHEV", result.getJSONArray("records").getJSONObject(0).getString("technology"));
    }

    private JSONObject find(JSONArray values, String type)
    {
        return values.stream().map(JSONObject.class::cast).filter(item -> type.equals(item.getString("type"))).findFirst().orElseThrow();
    }
}
