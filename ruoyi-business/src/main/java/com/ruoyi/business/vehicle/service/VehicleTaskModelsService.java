package com.ruoyi.business.vehicle.service;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import com.ruoyi.business.vehicle.domain.VehicleCollectTask;
import com.ruoyi.business.vehicle.domain.VehicleModel;
import com.ruoyi.business.vehicle.mapper.VehicleCollectModelMapper;
import com.ruoyi.business.vehicle.mapper.VehicleCollectTaskMapper;
import com.ruoyi.business.vehicle.mapper.VehicleModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Uses exact, recorded identifiers only when a legacy task needs a relation backfill. */
@Service
public class VehicleTaskModelsService
{
    private static final Logger LOG = LoggerFactory.getLogger(VehicleTaskModelsService.class);
    private final VehicleCollectTaskMapper taskMapper;
    private final VehicleCollectModelMapper relationMapper;
    private final VehicleModelMapper modelMapper;

    public VehicleTaskModelsService(VehicleCollectTaskMapper taskMapper, VehicleCollectModelMapper relationMapper, VehicleModelMapper modelMapper)
    {
        this.taskMapper = taskMapper;
        this.relationMapper = relationMapper;
        this.modelMapper = modelMapper;
    }

    @Transactional
    public List<Map<String, Object>> listTaskModels(Long taskId)
    {
        List<Map<String, Object>> related = relationMapper.selectTaskModels(taskId);
        if (!related.isEmpty()) return related;

        VehicleCollectTask task = taskMapper.selectById(taskId);
        if (task == null || !"2".equals(task.getStatus()) || !"dongchedi".equalsIgnoreCase(task.getSourceCode()) || blank(task.getSeriesId()))
        {
            LOG.info("No safe legacy vehicle relation fallback: taskId={}", taskId);
            return related;
        }
        List<VehicleModel> legacyModels = modelMapper.selectByLegacyTask(taskId, "dongchedi", task.getSeriesId());
        if (legacyModels == null || legacyModels.isEmpty())
        {
            LOG.info("No verifiable legacy vehicle models found: taskId={} seriesId={}", taskId, task.getSeriesId());
            return Collections.emptyList();
        }
        for (VehicleModel model : legacyModels) relationMapper.upsert(taskId, model.getId(), "LEGACY_BACKFILL");
        return relationMapper.selectTaskModels(taskId);
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }
}
