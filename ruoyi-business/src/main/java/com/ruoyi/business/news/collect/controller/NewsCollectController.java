package com.ruoyi.business.news.collect.controller;

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
import com.ruoyi.business.news.collect.domain.NewsCollect;
import com.ruoyi.business.news.collect.service.INewsCollectService;

/**
 * 白名单官网新闻抓取 控制器
 * 
 * @author ruoyi
 */
@RestController
@RequestMapping("/business/news/collect")
public class NewsCollectController extends BaseController
{
    @Autowired
    private INewsCollectService newsCollectService;

    @PreAuthorize("@ss.hasPermi('business:news:collect:list')")
    @GetMapping("/list")
    public TableDataInfo list(NewsCollect newsCollect)
    {
        startPage();
        List<NewsCollect> list = newsCollectService.selectNewsCollectList(newsCollect);
        return getDataTable(list);
    }

    @PreAuthorize("@ss.hasPermi('business:news:collect:export')")
    @Log(title = "白名单官网新闻抓取", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, NewsCollect newsCollect)
    {
        List<NewsCollect> list = newsCollectService.selectNewsCollectList(newsCollect);
        ExcelUtil<NewsCollect> util = new ExcelUtil<NewsCollect>(NewsCollect.class);
        util.exportExcel(response, list, "白名单官网新闻抓取数据");
    }

    @PreAuthorize("@ss.hasPermi('business:news:collect:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(newsCollectService.selectNewsCollectById(id));
    }

    @PreAuthorize("@ss.hasPermi('business:news:collect:add')")
    @Log(title = "白名单官网新闻抓取", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody NewsCollect newsCollect)
    {
        return toAjax(newsCollectService.insertNewsCollect(newsCollect));
    }

    @PreAuthorize("@ss.hasPermi('business:news:collect:edit')")
    @Log(title = "白名单官网新闻抓取", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody NewsCollect newsCollect)
    {
        return toAjax(newsCollectService.updateNewsCollect(newsCollect));
    }

    @PreAuthorize("@ss.hasPermi('business:news:collect:remove')")
    @Log(title = "白名单官网新闻抓取", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        return toAjax(newsCollectService.deleteNewsCollectByIds(ids));
    }
}
