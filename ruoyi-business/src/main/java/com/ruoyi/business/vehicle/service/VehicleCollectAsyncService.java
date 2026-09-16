package com.ruoyi.business.vehicle.service;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import com.alibaba.fastjson2.JSON;
import com.ruoyi.business.agent.client.AgentVehicleServiceClient;
import com.ruoyi.business.knowledge.service.KnowledgeIngestService;
import com.ruoyi.business.vehicle.domain.VehicleCollectTask;
import com.ruoyi.business.vehicle.domain.VehicleModel;
import com.ruoyi.business.vehicle.dto.VehicleCarDto;
import com.ruoyi.business.vehicle.dto.VehicleSeriesDetailResponse;
import com.ruoyi.business.vehicle.mapper.VehicleCollectModelMapper;
import com.ruoyi.business.vehicle.mapper.VehicleCollectTaskMapper;
import com.ruoyi.business.vehicle.mapper.VehicleModelMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

/** Bounded per-series collection. MySQL receives the Python detail response directly. */
@Service
public class VehicleCollectAsyncService
{
    private static final Logger LOG = LoggerFactory.getLogger(VehicleCollectAsyncService.class);
    private final VehicleCollectTaskMapper taskMapper;
    private final VehicleModelMapper modelMapper;
    private final VehicleCollectModelMapper relationMapper;
    private final AgentVehicleServiceClient client;
    private final ThreadPoolTaskExecutor executor;
    private final KnowledgeIngestService knowledgeIngestService;
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    public VehicleCollectAsyncService(VehicleCollectTaskMapper taskMapper, VehicleModelMapper modelMapper,
        VehicleCollectModelMapper relationMapper, AgentVehicleServiceClient client,
        @Qualifier("vehicleCollectTaskExecutor") ThreadPoolTaskExecutor executor,
        KnowledgeIngestService knowledgeIngestService)
    {
        this.taskMapper = taskMapper;
        this.modelMapper = modelMapper;
        this.relationMapper = relationMapper;
        this.client = client;
        this.executor = executor;
        this.knowledgeIngestService = knowledgeIngestService;
    }

    public boolean submit(Long taskId)
    {
        if (!running.add(taskId)) return false;
        try
        {
            executor.execute(() -> run(taskId));
            return true;
        }
        catch (RuntimeException e)
        {
            running.remove(taskId);
            fail(taskId, "车辆采集任务队列已满，请稍后重试", new Summary());
            return false;
        }
    }

    private void run(Long taskId)
    {
        Summary summary = new Summary();
        try
        {
            VehicleCollectTask task = taskMapper.selectById(taskId);
            if (task == null || !"1".equals(task.getStatus())) return;
            VehicleSeriesDetailResponse response = client.details(task.getBrandName(), task.getSeriesId(), task.getSeriesName());
            summary.fetched = response.getCars() == null ? 0 : response.getCars().size();
            if (response.getCars() != null)
            {
                for (VehicleCarDto car : response.getCars())
                {
                    try { sync(task, response, car, summary); }
                    catch (Exception carError)
                    {
                        summary.failed++;
                        LOG.error("Vehicle model sync failed: taskId={} carId={}", taskId, car.getCarId(), carError);
                    }
                }
            }
            if (summary.failed > 0) fail(taskId, "部分车型同步失败：" + summary.failed + "/" + summary.fetched, summary);
            else finish(taskId, summary);
        }
        catch (Exception e)
        {
            LOG.error("Vehicle collect task failed: taskId={}", taskId, e);
            fail(taskId, "车辆采集服务处理失败，请稍后重试", summary);
        }
        finally { running.remove(taskId); }
    }

    private void sync(VehicleCollectTask task, VehicleSeriesDetailResponse response, VehicleCarDto car, Summary summary)
    {
        if (car.getCarId() == null || car.getCarId().isBlank()) throw new IllegalArgumentException("车型缺少懂车帝 car_id");
        VehicleModel incoming = toModel(task, response, car);
        VehicleModel existing = modelMapper.selectBySourceCarId("dongchedi", car.getCarId());
        VehicleModel persisted;
        if (existing == null)
        {
            modelMapper.insert(incoming);
            relationMapper.upsert(task.getId(), incoming.getId(), "INSERTED");
            summary.inserted++;
            persisted = incoming;
        }
        else
        {
            incoming.setId(existing.getId());
            incoming.setFirstSeenTaskId(existing.getFirstSeenTaskId());
            incoming.setFirstSeenTime(existing.getFirstSeenTime());
            if (sameBusinessContent(existing, incoming))
            {
                modelMapper.updateLastSeen(incoming);
                relationMapper.upsert(task.getId(), existing.getId(), "EXISTING");
                summary.existing++;
            }
            else
            {
                modelMapper.update(incoming);
                relationMapper.upsert(task.getId(), existing.getId(), "UPDATED");
                summary.updated++;
            }
            persisted = modelMapper.selectById(existing.getId());
            if (persisted == null) persisted = incoming;
        }
        publishToUnifiedKnowledge(persisted);
    }

    private void publishToUnifiedKnowledge(VehicleModel model)
    {
        try
        {
            knowledgeIngestService.submitCollectedVehicleModel(model.getId(), model.getBrandName(), model.getSeriesName(),
                model.getModelName(), model.getManufacturer(), model.getOfficialGuidePrice(), model.getLevel(),
                model.getEnergyType(), model.getInstrumentScreenSizeInch(), model.getInstrumentScreenStyle(),
                model.getCenterScreenSizeInch(), model.getCenterScreenMaterial(), model.getPassengerScreenSizeInch(),
                model.getRearScreenSizeInch(), model.getSourceUrl(), model.getDongchediCarId(),
                model.getDongchediSeriesId(), formatTime(model.getLastCrawledTime()), "vehicle-collector");
        }
        catch (Exception exception)
        {
            LOG.warn("Vehicle knowledge ingest skipped: modelId={} reason={}", model.getId(), exception.getMessage());
        }
    }

    private VehicleModel toModel(VehicleCollectTask task, VehicleSeriesDetailResponse response, VehicleCarDto car)
    {
        VehicleModel m = new VehicleModel();
        Date now = new Date();
        m.setSourceCode("dongchedi");
        m.setDongchediCarId(car.getCarId());
        m.setDongchediSeriesId(blank(response.getSeriesId()) ? task.getSeriesId() : response.getSeriesId());
        m.setBrandName(task.getBrandName());
        m.setSeriesName(blank(response.getSeriesName()) ? task.getSeriesName() : response.getSeriesName());
        m.setModelName(car.getModelName());
        m.setManufacturer(car.getManufacturer());
        m.setOfficialGuidePrice(car.getOfficialGuidePrice());
        m.setLevel(car.getLevel());
        m.setEnergyType(car.getEnergyType());
        m.setInstrumentScreenSizeInch(car.getInstrumentScreenSizeInch());
        m.setInstrumentScreenStyle(car.getInstrumentScreenStyle());
        m.setCenterScreenSizeInch(car.getCenterScreenSizeInch());
        m.setCenterScreenMaterial(car.getCenterScreenMaterial());
        m.setPassengerScreenSizeInch(car.getPassengerScreenSizeInch());
        m.setRearScreenSizeInch(car.getRearScreenSizeInch());
        m.setSourceUrl(response.getSourceUrl());
        m.setFieldSourcesJson(canonicalJson(car.getSources()));
        m.setFieldStatusJson(canonicalJson(car.getFieldStatus()));
        m.setRawJson(JSON.toJSONString(car));
        m.setFirstSeenTaskId(task.getId());
        m.setLastSeenTaskId(task.getId());
        m.setFirstSeenTime(now);
        m.setLastCrawledTime(now);
        m.setCreateBy("system");
        m.setUpdateBy("system");
        return m;
    }

    private static boolean sameBusinessContent(VehicleModel a, VehicleModel b)
    {
        return Objects.equals(a.getDongchediSeriesId(), b.getDongchediSeriesId())
            && Objects.equals(a.getBrandName(), b.getBrandName())
            && Objects.equals(a.getSeriesName(), b.getSeriesName())
            && Objects.equals(a.getModelName(), b.getModelName())
            && Objects.equals(a.getManufacturer(), b.getManufacturer())
            && Objects.equals(a.getOfficialGuidePrice(), b.getOfficialGuidePrice())
            && Objects.equals(a.getLevel(), b.getLevel())
            && Objects.equals(a.getEnergyType(), b.getEnergyType())
            && Objects.equals(a.getInstrumentScreenSizeInch(), b.getInstrumentScreenSizeInch())
            && Objects.equals(a.getInstrumentScreenStyle(), b.getInstrumentScreenStyle())
            && Objects.equals(a.getCenterScreenSizeInch(), b.getCenterScreenSizeInch())
            && Objects.equals(a.getCenterScreenMaterial(), b.getCenterScreenMaterial())
            && Objects.equals(a.getPassengerScreenSizeInch(), b.getPassengerScreenSizeInch())
            && Objects.equals(a.getRearScreenSizeInch(), b.getRearScreenSizeInch())
            && Objects.equals(a.getSourceUrl(), b.getSourceUrl())
            && Objects.equals(a.getFieldSourcesJson(), b.getFieldSourcesJson())
            && Objects.equals(a.getFieldStatusJson(), b.getFieldStatusJson());
    }

    private static String canonicalJson(Map<String, String> value)
    {
        return JSON.toJSONString(value == null ? Map.of() : new TreeMap<>(value));
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static String formatTime(Date value)
    {
        return value == null ? "" : new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(value);
    }

    private void finish(Long id, Summary s) { update(id, "2", "", s); }

    private void fail(Long id, String message, Summary s) { update(id, "3", message, s); }

    private void update(Long id, String status, String message, Summary s)
    {
        VehicleCollectTask u = new VehicleCollectTask();
        u.setId(id);
        u.setStatus(status);
        u.setCompletedTime(new Date());
        u.setFetchedCount(s.fetched);
        u.setInsertedCount(s.inserted);
        u.setUpdatedCount(s.updated);
        u.setExistingCount(s.existing);
        u.setFailedCount(s.failed);
        u.setErrorMessage(message);
        u.setUpdateBy("system");
        taskMapper.update(u);
    }

    static final class Summary { int fetched, inserted, updated, existing, failed; }
}
