package com.ruoyi.business.analysis.display.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.business.analysis.display.domain.DisplayAnalysis;
import com.ruoyi.business.analysis.display.mapper.DisplayAnalysisMapper;
import com.ruoyi.business.analysis.display.service.IDisplayAnalysisService;

/**
 * 车载显示分析-标准模板基础统计 服务实现
 * 
 * @author ruoyi
 */
@Service
public class DisplayAnalysisServiceImpl implements IDisplayAnalysisService
{
    @Autowired
    private DisplayAnalysisMapper displayAnalysisMapper;

    @Override
    public List<DisplayAnalysis> selectDisplayAnalysisList(DisplayAnalysis displayAnalysis)
    {
        return displayAnalysisMapper.selectDisplayAnalysisList(displayAnalysis);
    }

    @Override
    public DisplayAnalysis selectDisplayAnalysisById(Long id)
    {
        return displayAnalysisMapper.selectDisplayAnalysisById(id);
    }

    @Override
    public int insertDisplayAnalysis(DisplayAnalysis displayAnalysis)
    {
        return displayAnalysisMapper.insertDisplayAnalysis(displayAnalysis);
    }

    @Override
    public int updateDisplayAnalysis(DisplayAnalysis displayAnalysis)
    {
        return displayAnalysisMapper.updateDisplayAnalysis(displayAnalysis);
    }

    @Override
    public int deleteDisplayAnalysisById(Long id)
    {
        return displayAnalysisMapper.deleteDisplayAnalysisById(id);
    }

    @Override
    public int deleteDisplayAnalysisByIds(Long[] ids)
    {
        return displayAnalysisMapper.deleteDisplayAnalysisByIds(ids);
    }
}
