package com.ruoyi.business.vehicle.controller;

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;
import com.ruoyi.business.agent.client.AgentServiceClientException;
import com.ruoyi.business.agent.client.AgentVehicleServiceClient;
import com.ruoyi.business.vehicle.domain.VehicleCollectTask;
import com.ruoyi.business.vehicle.dto.VehicleBrandDto;
import com.ruoyi.business.vehicle.dto.VehicleSeriesListResponse;
import com.ruoyi.business.vehicle.mapper.VehicleCollectModelMapper;
import com.ruoyi.business.vehicle.mapper.VehicleCollectTaskMapper;
import com.ruoyi.business.vehicle.service.VehicleCollectAsyncService;
import com.ruoyi.business.vehicle.service.VehicleKnowledgePublishService;
import com.ruoyi.business.vehicle.service.VehicleTaskModelsService;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ContentDisposition;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/business/vehicle/collect")
public class VehicleCollectController extends BaseController
{
    private static final Logger LOG = LoggerFactory.getLogger(VehicleCollectController.class);
    private final VehicleCollectTaskMapper mapper;
    private final VehicleCollectModelMapper relationMapper;
    private final VehicleTaskModelsService taskModelsService;
    private final VehicleKnowledgePublishService knowledgePublishService;
    private final VehicleCollectAsyncService async;
    private final AgentVehicleServiceClient client;

    public VehicleCollectController(VehicleCollectTaskMapper mapper, VehicleCollectModelMapper relationMapper,
        VehicleTaskModelsService taskModelsService, VehicleKnowledgePublishService knowledgePublishService,
        VehicleCollectAsyncService async, AgentVehicleServiceClient client)
    {
        this.mapper = mapper;
        this.relationMapper = relationMapper;
        this.taskModelsService = taskModelsService;
        this.knowledgePublishService = knowledgePublishService;
        this.async = async;
        this.client = client;
    }

    @PreAuthorize("@ss.hasPermi('business:vehicle:collect:list')")
    @GetMapping("/auth-status")
    public AjaxResult authStatus()
    {
        try { return success(client.authStatus()); }
        catch (AgentServiceClientException exception) { return error(exception.getMessage()); }
    }

    @PreAuthorize("@ss.hasPermi('business:vehicle:collect:list')")
    @GetMapping("/brands")
    public AjaxResult brands()
    {
        try { return success(client.brands()); }
        catch (AgentServiceClientException exception) { return error(exception.getMessage()); }
    }

    @PreAuthorize("@ss.hasPermi('business:vehicle:collect:list')")
    @GetMapping("/series")
    public AjaxResult series(@RequestParam String brand)
    {
        if (brand == null || brand.isBlank()) return error("请选择品牌");
        try
        {
            VehicleSeriesListResponse response = client.series(brand);
            return success(response.getItems());
        }
        catch (AgentServiceClientException exception)
        {
            LOG.warn("Dongchedi series failed: brand={} code={} upstreamStatus={}", brand,
                exception.getErrorCode(), exception.getUpstreamStatus());
            return error(exception.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:vehicle:collect:add')")
    @PostMapping
    public AjaxResult create(@RequestBody VehicleCollectTask request)
    {
        if (blank(request.getBrandName()) || blank(request.getSeriesId()) || blank(request.getSeriesName()))
            return error("品牌、车系ID和车系名称不能为空");
        request.setSourceCode("dongchedi");
        request.setStatus("1");
        request.setStartedTime(new Date());
        request.setCreateBy(getUsername());
        mapper.insert(request);
        if (!async.submit(request.getId())) return error("车辆采集任务未能进入队列");
        return success(request);
    }

    @PreAuthorize("@ss.hasPermi('business:vehicle:collect:list')")
    @GetMapping("/list")
    public TableDataInfo list(VehicleCollectTask query)
    {
        startPage();
        return getDataTable(mapper.selectList(query));
    }

    @PreAuthorize("@ss.hasPermi('business:vehicle:collect:query')")
    @GetMapping("/{id}")
    public AjaxResult get(@PathVariable Long id)
    {
        return success(mapper.selectById(id));
    }

    @PreAuthorize("@ss.hasPermi('business:vehicle:collect:query')")
    @GetMapping("/{id}/models")
    public AjaxResult models(@PathVariable Long id)
    {
        return success(taskModelsService.listTaskModels(id));
    }

    @PreAuthorize("@ss.hasPermi('business:vehicle:collect:export')")
    @GetMapping("/{id}/export")
    public void export(@PathVariable Long id, HttpServletResponse response) throws Exception
    {
        VehicleCollectTask task = mapper.selectById(id);
        if (task == null)
        {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "车辆采集任务不存在");
            return;
        }
        List<Map<String, Object>> models = taskModelsService.listTaskModels(id);
        if (models == null || models.isEmpty())
        {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST, "本次任务没有可导出的车型");
            return;
        }
        AgentVehicleServiceClient.VehicleExport export;
        try
        {
            export = client.exportFromData(task.getBrandName(), task.getSeriesId(), task.getSeriesName(), models);
        }
        catch (AgentServiceClientException exception)
        {
            LOG.error("Dongchedi export failed: taskId={} brand={} seriesId={} code={} upstreamStatus={}", id,
                task.getBrandName(), task.getSeriesId(), exception.getErrorCode(), exception.getUpstreamStatus());
            throw exception;
        }
        String filename = "懂车帝_" + task.getBrandName() + "_" + task.getSeriesName() + ".xlsx";
        response.setContentType(AgentVehicleServiceClient.XLSX_CONTENT_TYPE);
        response.setHeader("Content-Disposition",
            ContentDisposition.attachment().filename(filename, StandardCharsets.UTF_8).build().toString());
        response.setContentLengthLong(export.content().length);
        response.getOutputStream().write(export.content());
    }

    @PreAuthorize("@ss.hasPermi('business:vehicle:collect:add')")
    @PostMapping("/{id}/publish")
    public AjaxResult publish(@PathVariable Long id)
    {
        try { return success(knowledgePublishService.publishTask(id, getUsername())); }
        catch (IllegalArgumentException | IllegalStateException exception) { return error(exception.getMessage()); }
    }

    @PreAuthorize("@ss.hasPermi('business:vehicle:collect:remove')")
    @PostMapping("/{id}/models/delete")
    public AjaxResult deleteModels(@PathVariable Long id, @RequestBody Map<String, List<Long>> request)
    {
        List<Long> modelIds = request.get("modelIds");
        return modelIds == null || modelIds.isEmpty() ? error("请选择要移除的车型")
            : toAjax(relationMapper.deleteSelected(id, modelIds));
    }

    @PreAuthorize("@ss.hasPermi('business:vehicle:collect:edit')")
    @PostMapping("/{id}/retry")
    public AjaxResult retry(@PathVariable Long id)
    {
        VehicleCollectTask task = mapper.selectById(id);
        if (task == null) return error("车辆采集任务不存在");
        if (!"3".equals(task.getStatus())) return error("仅失败任务可以重新采集");
        task.setStatus("1");
        task.setStartedTime(new Date());
        task.setCompletedTime(null);
        task.setFetchedCount(0);
        task.setInsertedCount(0);
        task.setUpdatedCount(0);
        task.setExistingCount(0);
        task.setFailedCount(0);
        task.setErrorMessage("");
        task.setUpdateBy(getUsername());
        mapper.update(task);
        if (!async.submit(id)) return error("车辆采集任务已在执行或队列已满");
        return success(task);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
