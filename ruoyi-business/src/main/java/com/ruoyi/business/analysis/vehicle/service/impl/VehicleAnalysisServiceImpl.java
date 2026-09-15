package com.ruoyi.business.analysis.vehicle.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.business.analysis.vehicle.domain.VehicleAnalysis;
import com.ruoyi.business.analysis.vehicle.mapper.VehicleAnalysisMapper;
import com.ruoyi.business.analysis.vehicle.service.IVehicleAnalysisService;

/**
 * 整车市场分析-基础统计、排名与趋势 服务实现
 * 
 * @author ruoyi
 */
@Service
public class VehicleAnalysisServiceImpl implements IVehicleAnalysisService
{
    @Autowired
    private VehicleAnalysisMapper vehicleAnalysisMapper;

    @Override
    public List<VehicleAnalysis> selectVehicleAnalysisList(VehicleAnalysis vehicleAnalysis)
    {
        return vehicleAnalysisMapper.selectVehicleAnalysisList(vehicleAnalysis);
    }

    @Override
    public VehicleAnalysis selectVehicleAnalysisById(Long id)
    {
        return vehicleAnalysisMapper.selectVehicleAnalysisById(id);
    }

    @Override
    public VehicleAnalysis selectVehicleAnalysisByDatasetId(String datasetId)
    {
        return vehicleAnalysisMapper.selectVehicleAnalysisByDatasetId(datasetId);
    }

    @Override
    public int insertVehicleAnalysis(VehicleAnalysis vehicleAnalysis)
    {
        return vehicleAnalysisMapper.insertVehicleAnalysis(vehicleAnalysis);
    }

    @Override
    public int updateVehicleAnalysis(VehicleAnalysis vehicleAnalysis)
    {
        return vehicleAnalysisMapper.updateVehicleAnalysis(vehicleAnalysis);
    }

    @Override
    public int deleteVehicleAnalysisById(Long id)
    {
        return vehicleAnalysisMapper.deleteVehicleAnalysisById(id);
    }

    @Override
    public int deleteVehicleAnalysisByIds(Long[] ids)
    {
        return vehicleAnalysisMapper.deleteVehicleAnalysisByIds(ids);
    }

    @Override
    public int deleteVehicleAnalysisByDatasetId(String datasetId)
    {
        return vehicleAnalysisMapper.deleteVehicleAnalysisByDatasetId(datasetId);
    }
}
