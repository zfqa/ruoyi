package com.ruoyi.business.agent.client;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.business.agent.config.AgentServiceProperties;
import org.springframework.stereotype.Component;

/** Internal, JSON-only bridge for the Python news executor. */
@Component
public class AgentNewsServiceClient
{
    private final AgentServiceProperties properties;
    private final HttpClient httpClient;

    public AgentNewsServiceClient(AgentServiceProperties properties)
    {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
    }

    public JSONObject crawl(String sourceName, boolean force, String publishTimeStart, String publishTimeEnd)
        throws AgentServiceClientException
    {
        JSONObject body = new JSONObject();
        body.put("source_name", sourceName); body.put("force", force);
        if (publishTimeStart != null && !publishTimeStart.isBlank()) body.put("publish_time_start", publishTimeStart);
        if (publishTimeEnd != null && !publishTimeEnd.isBlank()) body.put("publish_time_end", publishTimeEnd);
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint("/news/crawl"))
            .timeout(properties.getNewsReadTimeout()).header("Accept", "application/json")
            .header("Content-Type", "application/json");
        if (properties.getInternalToken() != null && !properties.getInternalToken().isBlank())
            builder.header("Authorization", "Bearer " + properties.getInternalToken());
        return send(builder.POST(HttpRequest.BodyPublishers.ofString(body.toJSONString(), StandardCharsets.UTF_8)).build());
    }

    public JSONObject sources() throws AgentServiceClientException
    {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint("/news/sources")).timeout(properties.getReadTimeout()).GET();
        if (properties.getInternalToken() != null && !properties.getInternalToken().isBlank()) builder.header("Authorization", "Bearer " + properties.getInternalToken());
        return send(builder.build());
    }

    /** Remove staging-store articles by canonical URL after RuoYi task cleanup. */
    public JSONObject deleteByCanonicalUrls(java.util.List<String> canonicalUrls) throws AgentServiceClientException
    {
        JSONObject body = new JSONObject();
        body.put("canonical_urls", canonicalUrls);
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint("/news/delete-by-urls"))
            .timeout(properties.getReadTimeout()).header("Accept", "application/json")
            .header("Content-Type", "application/json");
        if (properties.getInternalToken() != null && !properties.getInternalToken().isBlank())
            builder.header("Authorization", "Bearer " + properties.getInternalToken());
        return send(builder.POST(HttpRequest.BodyPublishers.ofString(body.toJSONString(), StandardCharsets.UTF_8)).build());
    }

    private JSONObject send(HttpRequest request) throws AgentServiceClientException
    {
        try
        {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300)
                throw new AgentServiceClientException("AGENT_NEWS_HTTP_ERROR", "新闻采集服务返回异常状态", response.statusCode(), null);
            JSONObject result = JSONObject.parseObject(response.body());
            if (result == null) throw new IllegalArgumentException("JSON object is null");
            return result;
        }
        catch (HttpTimeoutException e) { throw new AgentServiceClientException("AGENT_NEWS_TIMEOUT", "新闻采集服务响应超时", e); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AgentServiceClientException("AGENT_NEWS_INTERRUPTED", "新闻采集服务调用被中断", e); }
        catch (IOException e) { throw new AgentServiceClientException("AGENT_NEWS_CONNECT_FAILED", "新闻采集服务连接失败", e); }
        catch (AgentServiceClientException e) { throw e; }
        catch (Exception e) { throw new AgentServiceClientException("AGENT_NEWS_INVALID_RESPONSE", "新闻采集服务返回格式无效", e); }
    }
    private URI endpoint(String path) throws AgentServiceClientException
    {
        try { return URI.create(properties.normalizedBaseUrl() + path); }
        catch (RuntimeException e) { throw new AgentServiceClientException("AGENT_CONFIGURATION_ERROR", "新闻采集服务配置无效", e); }
    }
}


