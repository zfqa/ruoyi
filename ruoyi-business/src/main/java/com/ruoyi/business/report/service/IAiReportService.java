package com.ruoyi.business.report.service;

import java.util.List;
import com.ruoyi.business.report.domain.AiReport;

/**
 * AI分析与报告 服务层
 * 
 * @author ruoyi
 */
public interface IAiReportService
{
    public List<AiReport> selectAiReportList(AiReport aiReport);

    public AiReport selectAiReportById(Long id);

    public AiReport selectLatestAiReportByImportTaskId(Long importTaskId);

    public int insertAiReport(AiReport aiReport);

    public int updateAiReport(AiReport aiReport);

    public int deleteAiReportById(Long id);

    public int deleteAiReportByIds(Long[] ids);
}
