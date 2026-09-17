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
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.business.news.collect.domain.NewsCollect;
import com.ruoyi.business.news.collect.service.INewsCollectService;
import com.ruoyi.business.news.collect.service.NewsCollectAsyncService;
import com.ruoyi.business.news.collect.service.NewsKnowledgePublishService;
import com.ruoyi.business.agent.client.AgentNewsServiceClient;
import java.util.Date;
import java.util.Map;
import com.ruoyi.business.news.collect.mapper.NewsCollectArticleMapper;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

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
    @Autowired private NewsCollectAsyncService newsCollectAsyncService;
    @Autowired private AgentNewsServiceClient agentNewsServiceClient;
    @Autowired private NewsCollectArticleMapper newsCollectArticleMapper;
    @Autowired private NewsKnowledgePublishService newsKnowledgePublishService;

    @PreAuthorize("@ss.hasPermi('business:news:collect:edit')")
    @Log(title = "新闻批量入库", businessType = BusinessType.INSERT)
    @PostMapping("/{id}/knowledge")
    public AjaxResult publishTaskKnowledge(@PathVariable Long id)
    {
        try { return success(newsKnowledgePublishService.publishTask(id, getUsername())); }
        catch (IllegalArgumentException | IllegalStateException exception) { return error(exception.getMessage()); }
    }

    @PreAuthorize("@ss.hasPermi('business:news:collect:edit')")
    @Log(title = "新闻单条入库", businessType = BusinessType.INSERT)
    @PostMapping("/{id}/articles/{articleId}/knowledge")
    public AjaxResult publishArticleKnowledge(@PathVariable Long id, @PathVariable Long articleId)
    {
        try { return success(newsKnowledgePublishService.publishArticle(id, articleId, getUsername())); }
        catch (IllegalArgumentException | IllegalStateException exception) { return error(exception.getMessage()); }
    }

    @PreAuthorize("@ss.hasPermi('business:news:collect:query')")
    @GetMapping("/{id}/articles")
    public AjaxResult taskArticles(@PathVariable Long id)
    {
        List<Map<String, Object>> items = newsCollectArticleMapper.selectTaskArticles(id);
        return success(items);
    }

    @PreAuthorize("@ss.hasPermi('business:news:collect:remove')")
    @Log(title = "移除任务新闻", businessType = BusinessType.DELETE)
    @PostMapping("/{id}/articles/delete")
    public AjaxResult deleteSelectedArticles(@PathVariable Long id, @RequestBody Map<String, List<Long>> request)
    {
        List<Long> articleIds = request.get("articleIds");
        if (articleIds == null || articleIds.isEmpty()) return error("请选择要移除的新闻");
        try
        {
            return success(newsCollectService.deleteTaskArticles(id, articleIds, getUsername()));
        }
        catch (ServiceException exception)
        {
            return error(exception.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:news:collect:list')")
    @GetMapping("/sources")
    public AjaxResult sources() throws Exception
    {
        return success(agentNewsServiceClient.sources().getJSONArray("sources"));
    }

    /** Historical task filtering must remain available after a source is disabled. */
    @PreAuthorize("@ss.hasPermi('business:news:collect:list')")
    @GetMapping("/options/history-sources")
    public AjaxResult historicalSources()
    {
        return success(newsCollectService.selectHistoricalSourceNames());
    }

    @PreAuthorize("@ss.hasPermi('business:news:collect:add')")
    @PostMapping("/crawl")
    public AjaxResult crawl(@RequestBody NewsCollect request)
    {
        if (request.getSourceName() == null || request.getSourceName().isBlank()) return error("请选择新闻来源");
        AjaxResult sourceValidation = validateCollectableSource(request.getSourceName());
        if (sourceValidation != null) return sourceValidation;
        request.setTaskName("新闻采集 - " + request.getSourceName()); request.setTriggerType("MANUAL"); request.setStatus("1"); request.setStartedTime(new Date()); request.setCreateBy(getUsername());
        newsCollectService.insertNewsCollect(request);
        if (!newsCollectAsyncService.submit(request.getId())) return error("新闻采集任务未能进入队列");
        return success(request);
    }

    @PreAuthorize("@ss.hasPermi('business:news:collect:edit')")
    @PostMapping("/{id}/retry")
    public AjaxResult retry(@PathVariable Long id)
    {
        NewsCollect task = newsCollectService.selectNewsCollectById(id);
        if (task == null) return error("新闻采集任务不存在");
        if (!"3".equals(task.getStatus())) return error("仅失败任务可以重新采集");
        AjaxResult sourceValidation = validateCollectableSource(task.getSourceName());
        if (sourceValidation != null) return sourceValidation;
        task.setStatus("1"); task.setStartedTime(new Date()); task.setCompletedTime(null); task.setErrorMessage(""); task.setUpdateBy(getUsername());
        newsCollectService.updateNewsCollect(task);
        if (!newsCollectAsyncService.submit(id)) return error("新闻采集任务已在执行或队列已满");
        return success(task);
    }

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
        if (newsCollect.getSourceName() != null && !newsCollect.getSourceName().isBlank())
        {
            AjaxResult sourceValidation = validateCollectableSource(newsCollect.getSourceName());
            if (sourceValidation != null) return sourceValidation;
        }
        return toAjax(newsCollectService.insertNewsCollect(newsCollect));
    }

    /** Prevent raw business requests from scheduling a source hidden by the UI. */
    private AjaxResult validateCollectableSource(String sourceName)
    {
        try
        {
            JSONObject result = agentNewsServiceClient.sources();
            JSONArray sources = result == null ? null : result.getJSONArray("sources");
            if (sources != null)
            {
                for (int index = 0; index < sources.size(); index++)
                {
                    JSONObject source = sources.getJSONObject(index);
                    if (sourceName.equals(source.getString("name"))
                            && !Boolean.FALSE.equals(source.getBoolean("enabled"))) return null;
                }
            }
            return error("新闻来源不存在或已停用，不能创建采集任务");
        }
        catch (Exception exception)
        {
            return error("新闻来源配置暂不可用，请稍后重试");
        }
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
