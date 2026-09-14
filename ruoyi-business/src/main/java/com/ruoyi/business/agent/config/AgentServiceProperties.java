package com.ruoyi.business.agent.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** Python agent-service 的网络连接配置。 */
@Component
@ConfigurationProperties(prefix = "agent-service")
public class AgentServiceProperties
{
    private String baseUrl = "http://127.0.0.1:8000";
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration readTimeout = Duration.ofSeconds(300);
    /** 仅用于 /data/upload 等文档解析请求；解析可能持续数分钟。 */
    private Duration documentReadTimeout = Duration.ofSeconds(1800);
    /** 新闻抓取可能跨页访问多个官网，使用独立的受限超时。 */
    private Duration newsReadTimeout = Duration.ofSeconds(600);
    /** 懂车帝单车系参数页需要浏览器渲染，使用独立限时。 */
    private Duration vehicleReadTimeout = Duration.ofSeconds(600);
    /** 仅 Java 与 Python 服务间使用，绝不返回给浏览器。 */
    private String internalToken;

    public String getBaseUrl()
    {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl)
    {
        this.baseUrl = baseUrl;
    }

    public Duration getConnectTimeout()
    {
        return connectTimeout;
    }

    public void setConnectTimeout(Duration connectTimeout)
    {
        this.connectTimeout = connectTimeout;
    }

    public Duration getReadTimeout()
    {
        return readTimeout;
    }

    public void setReadTimeout(Duration readTimeout)
    {
        this.readTimeout = readTimeout;
    }

    public Duration getDocumentReadTimeout()
    {
        return documentReadTimeout;
    }

    public void setDocumentReadTimeout(Duration documentReadTimeout)
    {
        this.documentReadTimeout = documentReadTimeout;
    }

    public Duration getNewsReadTimeout() { return newsReadTimeout; }
    public void setNewsReadTimeout(Duration newsReadTimeout) { this.newsReadTimeout = newsReadTimeout; }
    public Duration getVehicleReadTimeout() { return vehicleReadTimeout; }
    public void setVehicleReadTimeout(Duration vehicleReadTimeout) { this.vehicleReadTimeout = vehicleReadTimeout; }
    public String getInternalToken() { return internalToken; }
    public void setInternalToken(String internalToken) { this.internalToken = internalToken; }

    public String normalizedBaseUrl()
    {
        if (baseUrl == null || baseUrl.isBlank())
        {
            throw new IllegalStateException("agent-service base-url 未配置");
        }
        return baseUrl.trim().replaceFirst("/+$", "");
    }
}


