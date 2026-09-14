package com.ruoyi.business.news.collect.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.ruoyi.business.knowledge.domain.KnowledgeIngestTask;
import com.ruoyi.business.knowledge.service.KnowledgeIngestService;
import com.ruoyi.business.news.article.NewsArticle;
import com.ruoyi.business.news.article.mapper.NewsArticleMapper;
import com.ruoyi.business.news.collect.domain.NewsCollect;
import com.ruoyi.business.news.collect.mapper.NewsCollectArticleMapper;
import com.ruoyi.business.news.collect.mapper.NewsCollectMapper;
import org.springframework.stereotype.Service;

/** Manually republishes collected news into the unified MySQL knowledge base. */
@Service
public class NewsKnowledgePublishService
{
    private final NewsCollectMapper taskMapper;
    private final NewsCollectArticleMapper relationMapper;
    private final NewsArticleMapper articleMapper;
    private final KnowledgeIngestService knowledgeIngestService;

    public NewsKnowledgePublishService(NewsCollectMapper taskMapper, NewsCollectArticleMapper relationMapper,
        NewsArticleMapper articleMapper, KnowledgeIngestService knowledgeIngestService)
    {
        this.taskMapper = taskMapper;
        this.relationMapper = relationMapper;
        this.articleMapper = articleMapper;
        this.knowledgeIngestService = knowledgeIngestService;
    }

    public Map<String, Object> publishTask(Long taskId, String username)
    {
        NewsCollect task = taskMapper.selectNewsCollectById(taskId);
        if (task == null) throw new IllegalArgumentException("新闻采集任务不存在");
        if (!"2".equals(task.getStatus())) throw new IllegalStateException("仅成功完成的采集任务可以批量入库");
        List<Long> articleIds = relationMapper.selectTaskArticleIds(taskId);
        if (articleIds == null || articleIds.isEmpty()) throw new IllegalArgumentException("本次任务没有可入库新闻");
        int submitted = 0; int reused = 0; int failed = 0;
        for (Long articleId : articleIds)
        {
            try
            {
                Map<String, Object> result = publishArticle(taskId, articleId, username);
                if (Boolean.TRUE.equals(result.get("reused"))) reused++; else submitted++;
            }
            catch (RuntimeException exception) { failed++; }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("taskId", taskId); result.put("total", articleIds.size()); result.put("submitted", submitted);
        result.put("reused", reused); result.put("failed", failed);
        return result;
    }

    public Map<String, Object> publishArticle(Long taskId, Long articleId, String username)
    {
        if (relationMapper.countTaskArticle(taskId, articleId) == 0)
            throw new IllegalArgumentException("该新闻不属于本次采集任务");
        NewsArticle article = articleMapper.selectById(articleId);
        if (article == null) throw new IllegalArgumentException("新闻不存在或已删除");
        boolean reused = relationMapper.countKnowledgeStored(articleId) > 0;
        String sourceUrl = firstNonBlank(article.getCanonicalUrl(), article.getOriginalUrl(), article.getUrl());
        try
        {
            KnowledgeIngestTask ingestTask = knowledgeIngestService.submitCollectedNews(article.getId(),
                article.getSourceName(), article.getSourceSite(), article.getTitle(), article.getContent(), sourceUrl,
                article.getPublishedAt(), article.getCrawledAt(), article.getContentHash(), username);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("taskId", taskId); result.put("articleId", articleId); result.put("reused", reused);
            result.put("ingestTaskId", ingestTask == null ? null : ingestTask.getId());
            result.put("status", ingestTask == null ? null : ingestTask.getStatus());
            return result;
        }
        catch (IllegalArgumentException exception) { throw exception; }
        catch (Exception exception) { throw new IllegalStateException("新闻入库失败：" + safeMessage(exception)); }
    }

    private String firstNonBlank(String... values)
    {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        throw new IllegalArgumentException("新闻缺少可追溯原文地址，无法入库");
    }

    private String safeMessage(Exception exception)
    {
        String message = exception.getMessage();
        return message == null || message.isBlank() ? "知识库服务异常" : message;
    }
}
