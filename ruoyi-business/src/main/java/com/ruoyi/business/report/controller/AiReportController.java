package com.ruoyi.business.report.controller;

import java.util.List;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.business.report.domain.AiReport;
import com.ruoyi.business.report.service.IAiReportService;

/**
 * AI分析与报告 控制器
 * 
 * @author ruoyi
 */
@RestController
@RequestMapping("/business/report")
public class AiReportController extends BaseController
{
    @Autowired
    private IAiReportService aiReportService;

    @PreAuthorize("@ss.hasPermi('business:report:list')")
    @GetMapping("/list")
    public TableDataInfo list(AiReport aiReport)
    {
        startPage();
        List<AiReport> list = aiReportService.selectAiReportList(aiReport);
        return getDataTable(list);
    }

    @PreAuthorize("@ss.hasPermi('business:report:export')")
    @Log(title = "AI分析与报告", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, AiReport aiReport)
    {
        List<AiReport> list = aiReportService.selectAiReportList(aiReport);
        ExcelUtil<AiReport> util = new ExcelUtil<AiReport>(AiReport.class);
        util.exportExcel(response, list, "AI分析与报告数据");
    }

    @PreAuthorize("@ss.hasPermi('business:report:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(aiReportService.selectAiReportById(id));
    }

    @PreAuthorize("@ss.hasPermi('business:report:query')")
    @GetMapping(value = "/by-import/{importTaskId}")
    public AjaxResult getByImportTask(@PathVariable("importTaskId") Long importTaskId)
    {
        AiReport report = aiReportService.selectLatestAiReportByImportTaskId(importTaskId);
        return report == null ? AjaxResult.error("该解析任务尚未生成报告") : success(report);
    }

    @PreAuthorize("@ss.hasPermi('business:report:add')")
    @Log(title = "AI分析与报告", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody AiReport aiReport)
    {
        return toAjax(aiReportService.insertAiReport(aiReport));
    }

    @PreAuthorize("@ss.hasPermi('business:report:edit')")
    @Log(title = "AI分析与报告", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody AiReport aiReport)
    {
        return toAjax(aiReportService.updateAiReport(aiReport));
    }

    @PreAuthorize("@ss.hasPermi('business:report:remove')")
    @Log(title = "AI分析与报告", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(aiReportService.deleteAiReportByIds(ids));
    }
}
