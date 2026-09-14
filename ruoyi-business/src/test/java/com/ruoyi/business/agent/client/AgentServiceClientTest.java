package com.ruoyi.business.agent.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;
import com.ruoyi.business.agent.config.AgentServiceProperties;
import com.ruoyi.business.knowledge.service.LlmRuntimeConfiguration;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class AgentServiceClientTest
{
    private HttpServer server;

    @AfterEach void stop() { if (server != null) server.stop(0); }

    @Test
    void documentRequestDisablesPythonStoresAndUsesUnifiedLlm() throws Exception
    {
        AtomicReference<String> body = new AtomicReference<>();
        AtomicReference<String> authorization = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/data/upload", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = "{\"status\":\"success\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        AgentServiceProperties properties = new AgentServiceProperties();
        properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setConnectTimeout(Duration.ofSeconds(1)); properties.setDocumentReadTimeout(Duration.ofSeconds(2));
        properties.setInternalToken("integration-token");
        LlmRuntimeConfiguration llm = new LlmRuntimeConfiguration("https://ark.example.com/v3/chat/completions", "unified-model", "secret-key");
        AgentServiceClient client = new AgentServiceClient(properties, llm);
        client.parseDocument(new MockMultipartFile("file", "demo.pdf", "application/pdf", "%PDF-test".getBytes(StandardCharsets.UTF_8)));

        assertEquals("Bearer integration-token", authorization.get());
        assertTrue(body.get().contains("name=\"index_to_kb\"\r\n\r\nfalse"));
        assertTrue(body.get().contains("name=\"persist_review\"\r\n\r\nfalse"));
        assertTrue(body.get().contains("name=\"llm_managed\"\r\n\r\ntrue"));
        assertTrue(body.get().contains("unified-model"));
        assertTrue(body.get().contains("secret-key"));
    }

    @Test
    void clientErrorDoesNotExposeUpstreamBody() throws Exception
    {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/health", exchange -> { byte[] value="secret traceback".getBytes(); exchange.sendResponseHeaders(500,value.length); exchange.getResponseBody().write(value); exchange.close(); });
        server.start();
        AgentServiceProperties properties = new AgentServiceProperties(); properties.setBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        AgentServiceClient client = new AgentServiceClient(properties, new LlmRuntimeConfiguration("https://ark.example.com/v3/chat/completions", "model", "key"));
        try { client.health(); }
        catch (AgentServiceClientException error) { assertEquals("AGENT_HTTP_ERROR", error.getErrorCode()); assertFalse(error.getMessage().contains("secret")); return; }
        throw new AssertionError("expected AgentServiceClientException");
    }
}
