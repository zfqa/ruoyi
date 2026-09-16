package com.ruoyi.business.vehicle.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface VehicleCollectModelMapper
{
    int upsert(@Param("taskId") Long taskId, @Param("modelId") Long modelId, @Param("operation") String operation);

    List<Map<String, Object>> selectTaskModels(@Param("taskId") Long taskId);

    List<Long> selectTaskModelIds(@Param("taskId") Long taskId);

    int countTaskModel(@Param("taskId") Long taskId, @Param("modelId") Long modelId);

    int countKnowledgeStored(@Param("modelId") Long modelId);

    int deleteSelected(@Param("taskId") Long taskId, @Param("modelIds") List<Long> modelIds);
}
