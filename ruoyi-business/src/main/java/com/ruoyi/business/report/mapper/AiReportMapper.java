package com.ruoyi.business.report.mapper;

import java.util.List;
import com.ruoyi.business.report.domain.AiReport;

/**
 * AI分析与报告 数据层
 * 
 * @author ruoyi
 */
public interface AiReportMapper
{
    public List<AiReport> selectAiReportList(AiReport aiReport);

    public AiReport selectAiReportById(Long id);

    public AiReport selectLatestAiReportByImportTaskId(Long importTaskId);

    public int insertAiReport(AiReport aiReport);

    public int updateAiReport(AiReport aiReport);

    public int deleteAiReportById(Long id);

    public int deleteAiReportByIds(Long[] ids);
}
