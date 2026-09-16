package com.ruoyi.business.report.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.business.report.domain.AiReport;
import com.ruoyi.business.report.mapper.AiReportMapper;
import com.ruoyi.business.report.service.IAiReportService;

/**
 * AI分析与报告 服务实现
 * 
 * @author ruoyi
 */
@Service
public class AiReportServiceImpl implements IAiReportService
{
    @Autowired
    private AiReportMapper aiReportMapper;

    @Override
    public List<AiReport> selectAiReportList(AiReport aiReport)
    {
        return aiReportMapper.selectAiReportList(aiReport);
    }

    @Override
    public AiReport selectAiReportById(Long id)
    {
        return aiReportMapper.selectAiReportById(id);
    }

    @Override
    public AiReport selectLatestAiReportByImportTaskId(Long importTaskId)
    {
        return aiReportMapper.selectLatestAiReportByImportTaskId(importTaskId);
    }

    @Override
    public AiReport selectLatestAiReportByTaskName(String taskName)
    {
        return aiReportMapper.selectLatestAiReportByTaskName(taskName);
    }

    @Override
    public int insertAiReport(AiReport aiReport)
    {
        return aiReportMapper.insertAiReport(aiReport);
    }

    @Override
    public int updateAiReport(AiReport aiReport)
    {
        return aiReportMapper.updateAiReport(aiReport);
    }

    @Override
    public int deleteAiReportById(Long id)
    {
        return aiReportMapper.deleteAiReportById(id);
    }

    @Override
    public int deleteAiReportByIds(Long[] ids)
    {
        return aiReportMapper.deleteAiReportByIds(ids);
    }
}
