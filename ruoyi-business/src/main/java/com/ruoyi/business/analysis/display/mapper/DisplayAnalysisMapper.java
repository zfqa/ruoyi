package com.ruoyi.business.analysis.display.mapper;

import java.util.List;
import com.ruoyi.business.analysis.display.domain.DisplayAnalysis;

/**
 * 车载显示分析-标准模板基础统计 数据层
 * 
 * @author ruoyi
 */
public interface DisplayAnalysisMapper
{
    public List<DisplayAnalysis> selectDisplayAnalysisList(DisplayAnalysis displayAnalysis);

    public DisplayAnalysis selectDisplayAnalysisById(Long id);

    public int insertDisplayAnalysis(DisplayAnalysis displayAnalysis);

    public int updateDisplayAnalysis(DisplayAnalysis displayAnalysis);

    public int deleteDisplayAnalysisById(Long id);

    public int deleteDisplayAnalysisByIds(Long[] ids);
}
