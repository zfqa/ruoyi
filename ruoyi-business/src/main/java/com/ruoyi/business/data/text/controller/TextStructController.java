package com.ruoyi.business.data.text.controller;

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
import com.ruoyi.business.data.text.domain.TextStruct;
import com.ruoyi.business.data.text.service.ITextStructService;

/**
 * 自由文本结构化 控制器
 * 
 * @author ruoyi
 */
@RestController
@RequestMapping("/business/data/text")
public class TextStructController extends BaseController
{
    @Autowired
    private ITextStructService textStructService;

    @PreAuthorize("@ss.hasPermi('business:data:text:list')")
    @GetMapping("/list")
    public TableDataInfo list(TextStruct textStruct)
    {
        startPage();
        List<TextStruct> list = textStructService.selectTextStructList(textStruct);
        return getDataTable(list);
    }

    @PreAuthorize("@ss.hasPermi('business:data:text:export')")
    @Log(title = "自由文本结构化", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, TextStruct textStruct)
    {
        List<TextStruct> list = textStructService.selectTextStructList(textStruct);
        ExcelUtil<TextStruct> util = new ExcelUtil<TextStruct>(TextStruct.class);
        util.exportExcel(response, list, "自由文本结构化数据");
    }

    @PreAuthorize("@ss.hasPermi('business:data:text:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(textStructService.selectTextStructById(id));
    }

    @PreAuthorize("@ss.hasPermi('business:data:text:add')")
    @Log(title = "自由文本结构化", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody TextStruct textStruct)
    {
        return toAjax(textStructService.insertTextStruct(textStruct));
    }

    @PreAuthorize("@ss.hasPermi('business:data:text:edit')")
    @Log(title = "自由文本结构化", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody TextStruct textStruct)
    {
        return toAjax(textStructService.updateTextStruct(textStruct));
    }

    @PreAuthorize("@ss.hasPermi('business:data:text:remove')")
    @Log(title = "自由文本结构化", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(textStructService.deleteTextStructByIds(ids));
    }
}
