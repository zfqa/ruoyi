package com.ruoyi.business.data.pdf.controller;

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
import com.ruoyi.business.data.pdf.domain.PdfParse;
import com.ruoyi.business.data.pdf.service.IPdfParseService;

/**
 * 文本型PDF正文与规则表格解析 控制器
 * 
 * @author ruoyi
 */
@RestController
@RequestMapping("/business/data/pdf")
public class PdfParseController extends BaseController
{
    @Autowired
    private IPdfParseService pdfParseService;

    @PreAuthorize("@ss.hasPermi('business:data:pdf:list')")
    @GetMapping("/list")
    public TableDataInfo list(PdfParse pdfParse)
    {
        startPage();
        List<PdfParse> list = pdfParseService.selectPdfParseList(pdfParse);
        return getDataTable(list);
    }

    @PreAuthorize("@ss.hasPermi('business:data:pdf:export')")
    @Log(title = "文本型PDF正文与规则表格解析", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, PdfParse pdfParse)
    {
        List<PdfParse> list = pdfParseService.selectPdfParseList(pdfParse);
        ExcelUtil<PdfParse> util = new ExcelUtil<PdfParse>(PdfParse.class);
        util.exportExcel(response, list, "文本型PDF正文与规则表格解析数据");
    }

    @PreAuthorize("@ss.hasPermi('business:data:pdf:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(pdfParseService.selectPdfParseById(id));
    }

    @PreAuthorize("@ss.hasPermi('business:data:pdf:add')")
    @Log(title = "文本型PDF正文与规则表格解析", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody PdfParse pdfParse)
    {
        return toAjax(pdfParseService.insertPdfParse(pdfParse));
    }

    @PreAuthorize("@ss.hasPermi('business:data:pdf:edit')")
    @Log(title = "文本型PDF正文与规则表格解析", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody PdfParse pdfParse)
    {
        return toAjax(pdfParseService.updatePdfParse(pdfParse));
    }

    @PreAuthorize("@ss.hasPermi('business:data:pdf:remove')")
    @Log(title = "文本型PDF正文与规则表格解析", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(pdfParseService.deletePdfParseByIds(ids));
    }
}
