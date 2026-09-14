package com.ruoyi.business.report.controller;

import java.util.List;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
import com.ruoyi.business.report.service.AiReportOfficeExportService;

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

    @Autowired
    private AiReportOfficeExportService officeExportService;

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

    @PreAuthorize("@ss.hasPermi('business:report:export')")
    @Log(title = "AI分析报告Office导出", businessType = BusinessType.EXPORT)
    @GetMapping("/{id}/export/{format}")
    public void exportOffice(@PathVariable Long id, @PathVariable String format, HttpServletResponse response)
        throws java.io.IOException
    {
        AiReport report = aiReportService.selectAiReportById(id);
        if (report == null) throw new IllegalArgumentException("报告不存在");
        if (!"2".equals(report.getStatus())) throw new IllegalArgumentException("仅支持导出生成成功的报告");
        String normalized = format == null ? "" : format.toLowerCase();
        String extension;
        if ("word".equals(normalized) || "docx".equals(normalized))
        {
            extension = "docx";
            response.setContentType("application/vnd.openxmlformats-officedocument.wordprocessingml.document");
        }
        else if ("ppt".equals(normalized) || "pptx".equals(normalized))
        {
            extension = "pptx";
            response.setContentType("application/vnd.openxmlformats-officedocument.presentationml.presentation");
        }
        else throw new IllegalArgumentException("导出格式仅支持word或ppt");
        String baseName = officeExportService.safeFileName(report.getTaskName());
        String encoded = URLEncoder.encode(baseName + "." + extension, StandardCharsets.UTF_8).replace("+", "%20");
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("Content-Disposition", "attachment; filename*=UTF-8''" + encoded);
        if ("docx".equals(extension)) officeExportService.writeWord(report, response.getOutputStream());
        else officeExportService.writePowerPoint(report, response.getOutputStream());
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
