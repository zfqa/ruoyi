package com.ruoyi.business.agent.client;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.business.agent.config.AgentServiceProperties;
import com.ruoyi.business.vehicle.dto.VehicleBrandDto;
import com.ruoyi.business.vehicle.dto.VehicleSeriesDetailResponse;
import com.ruoyi.business.vehicle.dto.VehicleSeriesListResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Typed, token-protected bridge for the Python Dongchedi vehicle service. */
@Component
public class AgentVehicleServiceClient
{
    private static final Logger LOG = LoggerFactory.getLogger(AgentVehicleServiceClient.class);
    public static final String XLSX_CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    public record VehicleExport(byte[] content) { }
    private final AgentServiceProperties properties;
    private final HttpClient httpClient;

    public AgentVehicleServiceClient(AgentServiceProperties properties)
    {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER).version(HttpClient.Version.HTTP_1_1).build();
    }

    public List<VehicleBrandDto> brands() throws AgentServiceClientException
    {
        return send("/api/vehicles/brands").getList("items", VehicleBrandDto.class);
    }

    public VehicleSeriesListResponse series(String brand) throws AgentServiceClientException
    {
        long startedAt = System.nanoTime();
        LOG.info("[vehicle-series] start brand={}", brand);
        try
        {
            LOG.info("[vehicle-series] python request start brand={}", brand);
            VehicleSeriesListResponse result = JSON.parseObject(
                send("/api/vehicles/series?brand=" + encode(brand)).toJSONString(), VehicleSeriesListResponse.class);
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
            int count = result == null || result.getItems() == null ? 0 : result.getItems().size();
            LOG.info("[vehicle-series] python response status=200 brand={} count={} elapsed={}ms", brand, count, elapsedMs);
            return result;
        }
        catch (AgentServiceClientException exception)
        {
            long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;
            LOG.warn("[vehicle-series] failed brand={} code={} elapsed={}ms", brand, exception.getErrorCode(), elapsedMs);
            throw exception;
        }
    }

    public VehicleSeriesDetailResponse details(String brand, String seriesId, String seriesName) throws AgentServiceClientException
    {
        String path = "/api/vehicles/series/" + encodePath(seriesId) + "/details?brand=" + encode(brand)
            + "&series_name=" + encode(seriesName);
        return JSON.parseObject(send(path).toJSONString(), VehicleSeriesDetailResponse.class);
    }

    /** Downloads Python's saved-series workbook; it never starts collection. */
    public VehicleExport export(String brand, String seriesId) throws AgentServiceClientException
    {
        String path = "/api/vehicles/export?brand=" + encode(brand) + "&series_id=" + encode(seriesId);
        URI endpoint = endpoint(path);
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint).timeout(properties.getVehicleReadTimeout())
            .header("Accept", XLSX_CONTENT_TYPE).GET();
        addInternalToken(builder);
        try
        {
            HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
            {
                String body = new String(response.body(), StandardCharsets.UTF_8);
                LOG.error("Dongchedi export upstream error: status={} endpoint={} brand={} seriesId={} body={}",
                    response.statusCode(), endpoint, brand, seriesId, truncate(body));
                throw new AgentServiceClientException("AGENT_VEHICLE_EXPORT_HTTP_ERROR", "车辆导出服务返回异常状态", response.statusCode(), null);
            }
            return new VehicleExport(response.body());
        }
        catch (HttpTimeoutException e) { throw new AgentServiceClientException("AGENT_VEHICLE_EXPORT_TIMEOUT", "车辆导出服务响应超时", e); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AgentServiceClientException("AGENT_VEHICLE_EXPORT_INTERRUPTED", "车辆导出服务调用被中断", e); }
        catch (IOException e) { throw new AgentServiceClientException("AGENT_VEHICLE_EXPORT_CONNECT_FAILED", "车辆导出服务连接失败", e); }
    }

    /**
     * Exports the exact models already persisted by RuoYi for one task. This
     * deliberately bypasses Python's local staging repository.
     */
    public VehicleExport exportFromData(String brand, String seriesId, String seriesName, List<java.util.Map<String, Object>> models)
        throws AgentServiceClientException
    {
        JSONObject payload = new JSONObject();
        payload.put("brand", brand);
        payload.put("series_id", seriesId);
        payload.put("series_name", seriesName);
        payload.put("models", models);
        URI endpoint = endpoint("/api/vehicles/export/from-data");
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint).timeout(properties.getVehicleReadTimeout())
            .header("Accept", XLSX_CONTENT_TYPE).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(payload.toJSONString(), StandardCharsets.UTF_8));
        addInternalToken(builder);
        try
        {
            HttpResponse<byte[]> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300)
            {
                String body = new String(response.body(), StandardCharsets.UTF_8);
                LOG.error("Dongchedi task export upstream error: status={} endpoint={} brand={} seriesId={} body={}",
                    response.statusCode(), endpoint, brand, seriesId, truncate(body));
                throw new AgentServiceClientException("AGENT_VEHICLE_EXPORT_HTTP_ERROR", "车辆导出服务返回异常状态", response.statusCode(), null);
            }
            return new VehicleExport(response.body());
        }
        catch (HttpTimeoutException e) { throw new AgentServiceClientException("AGENT_VEHICLE_EXPORT_TIMEOUT", "车辆导出服务响应超时", e); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AgentServiceClientException("AGENT_VEHICLE_EXPORT_INTERRUPTED", "车辆导出服务调用被中断", e); }
        catch (IOException e) { throw new AgentServiceClientException("AGENT_VEHICLE_EXPORT_CONNECT_FAILED", "车辆导出服务连接失败", e); }
    }

    private JSONObject send(String path) throws AgentServiceClientException
    {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint(path)).timeout(properties.getVehicleReadTimeout())
            .header("Accept", "application/json").GET();
        addInternalToken(builder);
        try
        {
            HttpResponse<String> response = httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300)
            {
                String body = response.body() == null ? "" : response.body();
                LOG.warn("Dongchedi vehicle upstream error: status={} path={} body={}",
                    response.statusCode(), path, truncate(body));
                throw new AgentServiceClientException(extractAgentErrorCode(body),
                    extractAgentErrorMessage(body, response.statusCode()), response.statusCode(), null);
            }
            JSONObject result = JSONObject.parseObject(response.body());
            if (result == null) throw new IllegalArgumentException("JSON object is null");
            return result;
        }
        catch (HttpTimeoutException e) { throw new AgentServiceClientException("AGENT_VEHICLE_TIMEOUT", "车辆采集服务响应超时", e); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AgentServiceClientException("AGENT_VEHICLE_INTERRUPTED", "车辆采集服务调用被中断", e); }
        catch (IOException e) { throw new AgentServiceClientException("AGENT_VEHICLE_CONNECT_FAILED", "车辆采集服务连接失败", e); }
        catch (AgentServiceClientException e) { throw e; }
        catch (Exception e) { throw new AgentServiceClientException("AGENT_VEHICLE_INVALID_RESPONSE", "车辆采集服务返回格式无效", e); }
    }

    private static String extractAgentErrorCode(String body)
    {
        JSONObject detail = parseAgentDetail(body);
        String code = detail == null ? null : detail.getString("code");
        if (code == null || code.isBlank()) return "AGENT_VEHICLE_HTTP_ERROR";
        return switch (code)
        {
            case "auth_required" -> "AGENT_VEHICLE_AUTH_REQUIRED";
            case "unsupported_brand" -> "AGENT_VEHICLE_UNSUPPORTED_BRAND";
            case "series_not_found" -> "AGENT_VEHICLE_SERIES_NOT_FOUND";
            case "page_load_failed" -> "AGENT_VEHICLE_PAGE_LOAD_FAILED";
            case "parse_failed" -> "AGENT_VEHICLE_PARSE_FAILED";
            case "upstream_error" -> "AGENT_VEHICLE_UPSTREAM_ERROR";
            default -> "AGENT_VEHICLE_HTTP_ERROR";
        };
    }

    private static String extractAgentErrorMessage(String body, int status)
    {
        JSONObject detail = parseAgentDetail(body);
        if (detail != null)
        {
            String message = detail.getString("message");
            if (message != null && !message.isBlank()) return message.trim();
        }
        if (status == 401) return "未找到有效登录状态，请先完成懂车帝人工登录。";
        if (status == 404) return "未找到对应车系数据。";
        return "车辆采集服务返回异常状态";
    }

    private static JSONObject parseAgentDetail(String body)
    {
        if (body == null || body.isBlank()) return null;
        try
        {
            JSONObject root = JSONObject.parseObject(body);
            if (root == null) return null;
            Object detail = root.get("detail");
            if (detail instanceof JSONObject object) return object;
            if (detail instanceof String text && !text.isBlank())
            {
                JSONObject wrapper = new JSONObject();
                wrapper.put("message", text.trim());
                return wrapper;
            }
            return null;
        }
        catch (Exception ignored) { return null; }
    }

    private URI endpoint(String path) throws AgentServiceClientException
    {
        try { return URI.create(properties.normalizedBaseUrl() + path); }
        catch (RuntimeException e) { throw new AgentServiceClientException("AGENT_CONFIGURATION_ERROR", "车辆采集服务配置无效", e); }
    }
    private void addInternalToken(HttpRequest.Builder builder)
    {
        if (properties.getInternalToken() != null && !properties.getInternalToken().isBlank())
            builder.header("Authorization", "Bearer " + properties.getInternalToken());
    }
    private static String truncate(String value)
    {
        return value == null ? "" : value.substring(0, Math.min(value.length(), 4096));
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static String encodePath(String value) { return encode(value).replace("+", "%20"); }
}
