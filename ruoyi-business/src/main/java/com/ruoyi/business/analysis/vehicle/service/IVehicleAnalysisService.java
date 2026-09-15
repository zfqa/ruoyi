package com.ruoyi.business.analysis.vehicle.service;

import java.util.List;
import com.ruoyi.business.analysis.vehicle.domain.VehicleAnalysis;

/**
 * 整车市场分析-基础统计、排名与趋势 服务层
 * 
 * @author ruoyi
 */
public interface IVehicleAnalysisService
{
    public List<VehicleAnalysis> selectVehicleAnalysisList(VehicleAnalysis vehicleAnalysis);

    public VehicleAnalysis selectVehicleAnalysisById(Long id);

    public VehicleAnalysis selectVehicleAnalysisByDatasetId(String datasetId);

    public int insertVehicleAnalysis(VehicleAnalysis vehicleAnalysis);

    public int updateVehicleAnalysis(VehicleAnalysis vehicleAnalysis);

    public int deleteVehicleAnalysisById(Long id);

    public int deleteVehicleAnalysisByIds(Long[] ids);

    public int deleteVehicleAnalysisByDatasetId(String datasetId);
}
