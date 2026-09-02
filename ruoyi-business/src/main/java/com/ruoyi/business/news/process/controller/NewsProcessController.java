package com.ruoyi.business.news.process.controller;

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
import com.ruoyi.business.news.process.domain.NewsProcess;
import com.ruoyi.business.news.process.service.INewsProcessService;

/**
 * 新闻清洗、分类与事件提取 控制器
 * 
 * @author ruoyi
 */
@RestController
@RequestMapping("/business/news/process")
public class NewsProcessController extends BaseController
{
    @Autowired
    private INewsProcessService newsProcessService;

    @PreAuthorize("@ss.hasPermi('business:news:process:list')")
    @GetMapping("/list")
    public TableDataInfo list(NewsProcess newsProcess)
    {
        startPage();
        List<NewsProcess> list = newsProcessService.selectNewsProcessList(newsProcess);
        return getDataTable(list);
    }

    @PreAuthorize("@ss.hasPermi('business:news:process:export')")
    @Log(title = "新闻清洗、分类与事件提取", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, NewsProcess newsProcess)
    {
        List<NewsProcess> list = newsProcessService.selectNewsProcessList(newsProcess);
        ExcelUtil<NewsProcess> util = new ExcelUtil<NewsProcess>(NewsProcess.class);
        util.exportExcel(response, list, "新闻清洗、分类与事件提取数据");
    }

    @PreAuthorize("@ss.hasPermi('business:news:process:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(newsProcessService.selectNewsProcessById(id));
    }

    @PreAuthorize("@ss.hasPermi('business:news:process:add')")
    @Log(title = "新闻清洗、分类与事件提取", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody NewsProcess newsProcess)
    {
        return toAjax(newsProcessService.insertNewsProcess(newsProcess));
    }

    @PreAuthorize("@ss.hasPermi('business:news:process:edit')")
    @Log(title = "新闻清洗、分类与事件提取", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody NewsProcess newsProcess)
    {
        return toAjax(newsProcessService.updateNewsProcess(newsProcess));
    }

    @PreAuthorize("@ss.hasPermi('business:news:process:remove')")
    @Log(title = "新闻清洗、分类与事件提取", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(newsProcessService.deleteNewsProcessByIds(ids));
    }
}
