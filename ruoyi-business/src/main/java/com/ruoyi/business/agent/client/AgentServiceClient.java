package com.ruoyi.business.agent.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.business.agent.config.AgentServiceProperties;
import com.ruoyi.business.knowledge.service.LlmRuntimeConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * 面向 Python agent-service 的最小 HTTP 客户端。
 * 不复制调用方的 Authorization、Cookie 或 Redis token；仅发送文件和 index_to_kb=false。
 */
@Component
public class AgentServiceClient
{
    private final AgentServiceProperties properties;
    private final HttpClient httpClient;
    private final LlmRuntimeConfiguration llm;

    @Autowired
    public AgentServiceClient(AgentServiceProperties properties, LlmRuntimeConfiguration llm)
    {
        this(properties, llm, HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build());
    }

    AgentServiceClient(AgentServiceProperties properties, LlmRuntimeConfiguration llm, HttpClient httpClient)
    {
        this.properties = properties;
        this.llm = llm;
        this.httpClient = httpClient;
    }

    public JSONObject health() throws AgentServiceClientException
    {
        HttpRequest request = HttpRequest.newBuilder(endpoint("/health"))
            .timeout(properties.getReadTimeout()).header("Accept", "application/json").GET().build();
        return send(request);
    }

    public JSONObject parseDocument(MultipartFile file) throws AgentServiceClientException
    {
        String boundary = "----RuoYiAgentService" + UUID.randomUUID().toString().replace("-", "");
        HttpRequest request;
        try
        {
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint("/data/upload")).timeout(properties.getDocumentReadTimeout())
                .header("Accept", "application/json")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(buildMultipartBody(file.getBytes(), file.getOriginalFilename(), file.getContentType(), boundary)));
            addInternalAuthorization(builder);
            request = builder.build();
        }
        catch (IOException e)
        {
            throw new AgentServiceClientException("AGENT_FILE_READ_FAILED", "读取上传文件失败", e);
        }
        return send(request);
    }

    /** Parse a file already stored by RuoYi so asynchronous workers never retain a request-scoped MultipartFile. */
    public JSONObject parseDocument(Path path, String originalName, String contentType) throws AgentServiceClientException
    {
        String boundary = "----RuoYiAgentService" + UUID.randomUUID().toString().replace("-", "");
        try
        {
            HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint("/data/upload"))
                .timeout(properties.getDocumentReadTimeout()).header("Accept", "application/json")
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(buildMultipartBody(Files.readAllBytes(path), originalName, contentType, boundary)));
            addInternalAuthorization(builder);
            return send(builder.build());
        }
        catch (IOException e)
        {
            throw new AgentServiceClientException("AGENT_FILE_READ_FAILED", "读取已保存文档失败", e);
        }
    }

    private JSONObject send(HttpRequest request) throws AgentServiceClientException
    {
        final HttpResponse<String> response;
        try
        {
            response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        }
        catch (HttpTimeoutException e)
        {
            throw new AgentServiceClientException("AGENT_TIMEOUT", "文档解析服务响应超时", e);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new AgentServiceClientException("AGENT_INTERRUPTED", "文档解析服务调用被中断", e);
        }
        catch (IOException e)
        {
            throw new AgentServiceClientException("AGENT_CONNECT_FAILED", "文档解析服务连接失败", e);
        }

        if (response.statusCode() < 200 || response.statusCode() >= 300)
        {
            throw new AgentServiceClientException("AGENT_HTTP_ERROR", "文档解析服务返回异常状态", response.statusCode(), null);
        }
        try
        {
            JSONObject result = JSONObject.parseObject(response.body());
            if (result == null)
            {
                throw new IllegalArgumentException("JSON object is null");
            }
            return result;
        }
        catch (Exception e)
        {
            throw new AgentServiceClientException("AGENT_INVALID_RESPONSE", "文档解析服务返回格式无效", e);
        }
    }

    private URI endpoint(String path) throws AgentServiceClientException
    {
        try
        {
            return URI.create(properties.normalizedBaseUrl() + path);
        }
        catch (RuntimeException e)
        {
            throw new AgentServiceClientException("AGENT_CONFIGURATION_ERROR", "文档解析服务配置无效", e);
        }
    }

    private byte[] buildMultipartBody(byte[] content, String originalName, String suppliedContentType, String boundary)
    {
        String safeFilename = safeFilename(originalName);
        String contentType = suppliedContentType;
        if (contentType == null || contentType.isBlank() || contentType.contains("\r") || contentType.contains("\n"))
        {
            contentType = "application/octet-stream";
        }
        String llmApiKey = llm.getApiKey() == null ? "" : llm.getApiKey();
        byte[] prefix = (formField(boundary, "index_to_kb", "false")
            + formField(boundary, "persist_review", "false")
            + formField(boundary, "llm_managed", "true")
            + formField(boundary, "llm_api_url", llm.getOpenAiBaseUrl())
            + formField(boundary, "llm_model", llm.getModel())
            + formField(boundary, "llm_api_key", llmApiKey)
            + "--" + boundary + "\r\n"
            + "Content-Disposition: form-data; name=\"file\"; filename=\"" + safeFilename + "\"\r\n"
            + "Content-Type: " + contentType + "\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] body = new byte[prefix.length + content.length + suffix.length];
        System.arraycopy(prefix, 0, body, 0, prefix.length);
        System.arraycopy(content, 0, body, prefix.length, content.length);
        System.arraycopy(suffix, 0, body, prefix.length + content.length, suffix.length);
        return body;
    }

    private String formField(String boundary, String name, String value)
    {
        String safeValue = value == null ? "" : value.replace("\r", "").replace("\n", "");
        return "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name
            + "\"\r\n\r\n" + safeValue + "\r\n";
    }

    private void addInternalAuthorization(HttpRequest.Builder builder)
    {
        if (properties.getInternalToken() != null && !properties.getInternalToken().isBlank())
            builder.header("Authorization", "Bearer " + properties.getInternalToken());
    }

    private String safeFilename(String filename)
    {
        String value = filename == null || filename.isBlank() ? "document" : filename;
        value = value.replace('\\', '/');
        value = value.substring(value.lastIndexOf('/') + 1).replaceAll("[\\r\\n\"]", "_");
        return value.isBlank() ? "document" : value;
    }
}

