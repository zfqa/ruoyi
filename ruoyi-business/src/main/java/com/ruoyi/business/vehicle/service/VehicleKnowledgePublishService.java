package com.ruoyi.business.vehicle.service;

import java.text.SimpleDateFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.ruoyi.business.knowledge.domain.KnowledgeIngestTask;
import com.ruoyi.business.knowledge.service.KnowledgeIngestService;
import com.ruoyi.business.vehicle.domain.VehicleCollectTask;
import com.ruoyi.business.vehicle.domain.VehicleModel;
import com.ruoyi.business.vehicle.mapper.VehicleCollectModelMapper;
import com.ruoyi.business.vehicle.mapper.VehicleCollectTaskMapper;
import com.ruoyi.business.vehicle.mapper.VehicleModelMapper;
import org.springframework.stereotype.Service;

/** Manually republishes collected Dongchedi models into the unified MySQL knowledge base. */
@Service
public class VehicleKnowledgePublishService
{
    private final VehicleCollectTaskMapper taskMapper;
    private final VehicleCollectModelMapper relationMapper;
    private final VehicleModelMapper modelMapper;
    private final KnowledgeIngestService knowledgeIngestService;

    public VehicleKnowledgePublishService(VehicleCollectTaskMapper taskMapper, VehicleCollectModelMapper relationMapper,
        VehicleModelMapper modelMapper, KnowledgeIngestService knowledgeIngestService)
    {
        this.taskMapper = taskMapper;
        this.relationMapper = relationMapper;
        this.modelMapper = modelMapper;
        this.knowledgeIngestService = knowledgeIngestService;
    }

    public Map<String, Object> publishTask(Long taskId, String username)
    {
        VehicleCollectTask task = taskMapper.selectById(taskId);
        if (task == null) throw new IllegalArgumentException("车辆采集任务不存在");
        if (!"2".equals(task.getStatus())) throw new IllegalStateException("仅成功完成的采集任务可以批量入库");
        List<Long> modelIds = relationMapper.selectTaskModelIds(taskId);
        if (modelIds == null || modelIds.isEmpty()) throw new IllegalArgumentException("本次任务没有可入库车型");
        int submitted = 0; int reused = 0; int failed = 0;
        for (Long modelId : modelIds)
        {
            try
            {
                Map<String, Object> result = publishModel(taskId, modelId, username);
                if (Boolean.TRUE.equals(result.get("reused"))) reused++; else submitted++;
            }
            catch (RuntimeException exception) { failed++; }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId); result.put("total", modelIds.size()); result.put("submitted", submitted);
        result.put("reused", reused); result.put("failed", failed);
        return result;
    }

    public Map<String, Object> publishModel(Long taskId, Long modelId, String username)
    {
        if (relationMapper.countTaskModel(taskId, modelId) == 0)
            throw new IllegalArgumentException("该车型不属于本次采集任务");
        VehicleModel model = modelMapper.selectById(modelId);
        if (model == null) throw new IllegalArgumentException("车型不存在或已删除");
        boolean reused = relationMapper.countKnowledgeStored(modelId) > 0;
        try
        {
            KnowledgeIngestTask ingestTask = knowledgeIngestService.submitCollectedVehicleModel(
                model.getId(), model.getBrandName(), model.getSeriesName(), model.getModelName(),
                model.getManufacturer(), model.getOfficialGuidePrice(), model.getLevel(), model.getEnergyType(),
                model.getInstrumentScreenSizeInch(), model.getInstrumentScreenStyle(),
                model.getCenterScreenSizeInch(), model.getCenterScreenMaterial(),
                model.getPassengerScreenSizeInch(), model.getRearScreenSizeInch(), model.getSourceUrl(),
                model.getDongchediCarId(), model.getDongchediSeriesId(), formatTime(model.getLastCrawledTime()),
                username);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("taskId", taskId); result.put("modelId", modelId); result.put("reused", reused);
            result.put("ingestTaskId", ingestTask == null ? null : ingestTask.getId());
            result.put("status", ingestTask == null ? null : ingestTask.getStatus());
            return result;
        }
        catch (IllegalArgumentException exception) { throw exception; }
        catch (Exception exception) { throw new IllegalStateException("车型入库失败：" + safeMessage(exception)); }
    }

    private String formatTime(java.util.Date value)
    {
        return value == null ? "" : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(value);
    }

    private String safeMessage(Exception exception)
    {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "知识库服务异常" : message;
    }
}
