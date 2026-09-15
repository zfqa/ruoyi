package com.ruoyi.business.analysis.vehicle.service;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import com.ruoyi.business.knowledge.service.LlmRuntimeConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/**
 * 访问本机 Python 市场分析服务。所有浏览器请求必须先经过 Spring Security。
 */
@Service
public class MarketAgentGateway
{
    private final HttpClient client;
    private final String baseUrl;
    private final Duration requestTimeout;
    private final LlmRuntimeConfiguration llmConfiguration;

    @Autowired
    public MarketAgentGateway(
        @Value("${business.market-agent.base-url:http://127.0.0.1:8001/api}") String baseUrl,
        @Value("${business.market-agent.connect-timeout-seconds:5}") long connectTimeoutSeconds,
        @Value("${business.market-agent.request-timeout-seconds:600}") long requestTimeoutSeconds,
        LlmRuntimeConfiguration llmConfiguration)
    {
        this(baseUrl, connectTimeoutSeconds, requestTimeoutSeconds, llmConfiguration, true);
    }

    /** 供不启动Spring容器的网关集成测试使用。 */
    public MarketAgentGateway(String baseUrl, long connectTimeoutSeconds, long requestTimeoutSeconds)
    {
        this(baseUrl, connectTimeoutSeconds, requestTimeoutSeconds, null, true);
    }

    private MarketAgentGateway(String baseUrl, long connectTimeoutSeconds, long requestTimeoutSeconds,
        LlmRuntimeConfiguration llmConfiguration, boolean ignored)
    {
        this.baseUrl = baseUrl.replaceAll("/+$", "");
        this.requestTimeout = Duration.ofSeconds(requestTimeoutSeconds);
        this.llmConfiguration = llmConfiguration;
        this.client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds))
            // Uvicorn仅提供HTTP/1.1；禁止JDK HttpClient发送h2c升级请求，
            // 否则带请求体的multipart会在升级握手阶段被当成空请求。
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    }

    public AgentResponse get(String path, String query) throws IOException, InterruptedException
    {
        return send(baseRequest(uri(path, query)).GET().build());
    }

    public AgentResponse delete(String path) throws IOException, InterruptedException
    {
        return send(baseRequest(uri(path, null)).DELETE().build());
    }

    public AgentResponse postJson(String path, String query, String json) throws IOException, InterruptedException
    {
        HttpRequest request = baseRequest(uri(path, query))
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .POST(HttpRequest.BodyPublishers.ofString(json == null ? "{}" : json, StandardCharsets.UTF_8))
            .build();
        return send(request);
    }

    public AgentResponse putJson(String path, String query, String json) throws IOException, InterruptedException
    {
        HttpRequest request = baseRequest(uri(path, query))
            .header("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .PUT(HttpRequest.BodyPublishers.ofString(json == null ? "{}" : json, StandardCharsets.UTF_8))
            .build();
        return send(request);
    }

    public AgentResponse postMultipart(String path, String fieldName, MultipartFile[] files)
        throws IOException, InterruptedException
    {
        String boundary = "----RuoYiMarketAgent" + System.nanoTime();
        List<HttpRequest.BodyPublisher> parts = new ArrayList<>();
        for (MultipartFile file : files)
        {
            String originalName = file.getOriginalFilename() == null ? "upload.xlsx" : file.getOriginalFilename();
            String extension = "";
            int dot = originalName.lastIndexOf('.');
            if (dot >= 0 && dot < originalName.length() - 1)
            {
                extension = originalName.substring(dot).replaceAll("[^A-Za-z0-9._-]", "_");
            }
            String fallbackName = "upload" + extension;
            String encodedName = URLEncoder.encode(originalName, StandardCharsets.UTF_8).replace("+", "%20");
            String contentType = file.getContentType() == null
                ? MediaType.APPLICATION_OCTET_STREAM_VALUE : file.getContentType();
            String header = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + fallbackName
                + "\"; filename*=UTF-8''" + encodedName + "\r\n"
                + "Content-Type: " + contentType + "\r\n\r\n";
            parts.add(HttpRequest.BodyPublishers.ofString(header, StandardCharsets.UTF_8));
            parts.add(HttpRequest.BodyPublishers.ofByteArray(file.getBytes()));
            parts.add(HttpRequest.BodyPublishers.ofString("\r\n", StandardCharsets.UTF_8));
        }
        parts.add(HttpRequest.BodyPublishers.ofString("--" + boundary + "--\r\n", StandardCharsets.UTF_8));
        HttpRequest request = baseRequest(uri(path, null))
            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
            .POST(HttpRequest.BodyPublishers.concat(parts.toArray(new HttpRequest.BodyPublisher[0])))
            .build();
        return send(request);
    }

    public BinaryResponse getBinary(String path) throws IOException, InterruptedException
    {
        HttpRequest request = baseRequest(uri(path, null)).GET().build();
        HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
        return new BinaryResponse(response.statusCode(), response.body(),
            response.headers().firstValue("content-type").orElse(MediaType.APPLICATION_OCTET_STREAM_VALUE),
            response.headers().firstValue("content-disposition").orElse(null));
    }

    private AgentResponse send(HttpRequest request) throws IOException, InterruptedException
    {
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return new AgentResponse(response.statusCode(), response.body());
    }

    private HttpRequest.Builder baseRequest(URI uri)
    {
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(requestTimeout);
        if (llmConfiguration != null)
        {
            builder.header("X-Market-LLM-Managed", "true")
                .header("X-Market-LLM-Api-Url", llmConfiguration.getApiUrl())
                .header("X-Market-LLM-Model", llmConfiguration.getModel());
            if (llmConfiguration.getApiKey() != null && !llmConfiguration.getApiKey().isBlank())
                builder.header("X-Market-LLM-Api-Key", llmConfiguration.getApiKey());
        }
        return builder;
    }

    private URI uri(String path, String query)
    {
        String suffix = path.startsWith("/") ? path : "/" + path;
        String value = baseUrl + suffix;
        if (query != null && !query.isBlank())
        {
            value += "?" + query;
        }
        return URI.create(value);
    }

    public static String encodePath(String value)
    {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static class AgentResponse
    {
        private final int statusCode;
        private final String body;

        public AgentResponse(int statusCode, String body)
        {
            this.statusCode = statusCode;
            this.body = body;
        }

        public int getStatusCode() { return statusCode; }
        public String getBody() { return body; }
    }

    public static class BinaryResponse
    {
        private final int statusCode;
        private final byte[] body;
        private final String contentType;
        private final String contentDisposition;

        public BinaryResponse(int statusCode, byte[] body, String contentType, String contentDisposition)
        {
            this.statusCode = statusCode;
            this.body = body;
            this.contentType = contentType;
            this.contentDisposition = contentDisposition;
        }

        public int getStatusCode() { return statusCode; }
        public byte[] getBody() { return body; }
        public String getContentType() { return contentType; }
        public String getContentDisposition() { return contentDisposition; }
    }
}
