package com.ruoyi.business.analysis.display.controller;

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
import com.ruoyi.business.analysis.display.domain.DisplayAnalysis;
import com.ruoyi.business.analysis.display.service.IDisplayAnalysisService;

/**
 * 车载显示分析-标准模板基础统计 控制器
 * 
 * @author ruoyi
 */
@RestController
@RequestMapping("/business/analysis/display")
public class DisplayAnalysisController extends BaseController
{
    @Autowired
    private IDisplayAnalysisService displayAnalysisService;

    @PreAuthorize("@ss.hasPermi('business:analysis:display:list')")
    @GetMapping("/list")
    public TableDataInfo list(DisplayAnalysis displayAnalysis)
    {
        startPage();
        List<DisplayAnalysis> list = displayAnalysisService.selectDisplayAnalysisList(displayAnalysis);
        return getDataTable(list);
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:display:export')")
    @Log(title = "车载显示分析-标准模板基础统计", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, DisplayAnalysis displayAnalysis)
    {
        List<DisplayAnalysis> list = displayAnalysisService.selectDisplayAnalysisList(displayAnalysis);
        ExcelUtil<DisplayAnalysis> util = new ExcelUtil<DisplayAnalysis>(DisplayAnalysis.class);
        util.exportExcel(response, list, "车载显示分析-标准模板基础统计数据");
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:display:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(displayAnalysisService.selectDisplayAnalysisById(id));
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:display:add')")
    @Log(title = "车载显示分析-标准模板基础统计", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody DisplayAnalysis displayAnalysis)
    {
        return toAjax(displayAnalysisService.insertDisplayAnalysis(displayAnalysis));
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:display:edit')")
    @Log(title = "车载显示分析-标准模板基础统计", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody DisplayAnalysis displayAnalysis)
    {
        return toAjax(displayAnalysisService.updateDisplayAnalysis(displayAnalysis));
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:display:remove')")
    @Log(title = "车载显示分析-标准模板基础统计", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(displayAnalysisService.deleteDisplayAnalysisByIds(ids));
    }
}
