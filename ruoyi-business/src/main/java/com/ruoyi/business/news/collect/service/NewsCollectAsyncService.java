package com.ruoyi.business.news.collect.service;

import java.util.Date;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import com.alibaba.fastjson2.JSONObject;
import com.alibaba.fastjson2.JSONArray;
import com.ruoyi.business.agent.client.AgentNewsServiceClient;
import com.ruoyi.business.news.collect.domain.NewsCollect;
import com.ruoyi.business.news.collect.mapper.NewsCollectMapper;
import com.ruoyi.business.news.article.NewsArticle;
import com.ruoyi.business.news.article.mapper.NewsArticleMapper;
import com.ruoyi.business.news.collect.mapper.NewsCollectArticleMapper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Crawl stages articles for preview only. Main-library statistics and unified
 * knowledge ingest both finalize when the user clicks publish
 * ({@link NewsKnowledgePublishService}).
 */
@Service
public class NewsCollectAsyncService
{
    public static final String PENDING_INSERTED = "PENDING_INSERTED";
    public static final String PENDING_UPDATED = "PENDING_UPDATED";
    public static final String PENDING_EXISTING = "PENDING_EXISTING";

    private static final Logger log = LoggerFactory.getLogger(NewsCollectAsyncService.class);
    private final NewsCollectMapper mapper;
    private final NewsArticleMapper articleMapper;
    private final NewsCollectArticleMapper relationMapper;
    private final AgentNewsServiceClient client;
    private final ThreadPoolTaskExecutor executor;
    private final Set<Long> running = ConcurrentHashMap.newKeySet();

    public NewsCollectAsyncService(NewsCollectMapper mapper, NewsArticleMapper articleMapper,
        NewsCollectArticleMapper relationMapper, AgentNewsServiceClient client,
        @Qualifier("newsCollectTaskExecutor") ThreadPoolTaskExecutor executor)
    {
        this.mapper = mapper;
        this.articleMapper = articleMapper;
        this.relationMapper = relationMapper;
        this.client = client;
        this.executor = executor;
    }

    public boolean submit(Long id)
    {
        if (!running.add(id)) return false;
        try
        {
            executor.execute(() -> run(id));
            return true;
        }
        catch (RuntimeException e)
        {
            running.remove(id);
            fail(id, "新闻采集任务队列已满，请稍后重试", null);
            return false;
        }
    }

    private void run(Long id)
    {
        JSONObject stats = null;
        try
        {
            NewsCollect task = mapper.selectNewsCollectById(id);
            if (task == null || !"1".equals(task.getStatus())) return;
            JSONObject result = client.crawl(task.getSourceName(), Boolean.TRUE.equals(task.getForce()),
                task.getPublishTimeStart(), task.getPublishTimeEnd());
            stats = result.getJSONObject("stats");
            stageArticles(task.getId(), result.getJSONArray("articles"));
            finish(task, result.getString("crawl_run_id"), stats);
        }
        catch (Exception e)
        {
            log.error("News collect task failed: taskId={}", id, e);
            fail(id, "新闻采集服务处理失败，请稍后重试", stats);
        }
        finally
        {
            running.remove(id);
        }
    }

    private void finish(NewsCollect task, String runId, JSONObject stats)
    {
        // Main-library counts stay zero until manual publish.
        NewsCollect u = taskResult(task.getId(), "2", stats, 0, 0, 0);
        u.setCompletedTime(new Date());
        u.setCrawlRunId(runId);
        u.setErrorMessage("");
        u.setUpdateBy("system");
        mapper.updateNewsCollectResult(u);
    }

    private void fail(Long id, String message, JSONObject stats)
    {
        NewsCollect u = taskResult(id, "3", stats, 0, 0, 0);
        u.setCompletedTime(new Date());
        u.setErrorMessage(message);
        u.setUpdateBy("system");
        mapper.updateNewsCollectResult(u);
    }

    private NewsCollect taskResult(Long id, String status, JSONObject stats,
        int mysqlInserted, int mysqlUpdated, int mysqlExisting)
    {
        NewsCollect u = new NewsCollect();
        u.setId(id);
        u.setStatus(status);
        u.setFetchedCount(stats == null ? 0 : stats.getIntValue("fetched"));
        u.setInsertedCount(stats == null ? 0 : stats.getIntValue("inserted"));
        u.setUpdatedCount(stats == null ? 0 : stats.getIntValue("updated"));
        u.setDuplicateCount(stats == null ? 0 : stats.getIntValue("duplicate"));
        u.setFilteredCount(stats == null ? 0 : stats.getIntValue("filtered"));
        u.setFailedCount(stats == null ? 0 : stats.getIntValue("failed"));
        u.setMysqlInsertedCount(mysqlInserted);
        u.setMysqlUpdatedCount(mysqlUpdated);
        u.setMysqlExistingCount(mysqlExisting);
        u.setStatisticsVersion("V2");
        return u;
    }

    /** Stage crawl hits for preview; do not finalize main-library statistics. */
    private void stageArticles(Long taskId, JSONArray items)
    {
        if (items == null) return;
        for (int i = 0; i < items.size(); i++)
        {
            JSONObject j = items.getJSONObject(i);
            NewsArticle article = new NewsArticle();
            article.setSourceName(j.getString("source_name"));
            article.setSourceSite(j.getString("source_site"));
            article.setTitle(j.getString("title"));
            article.setContent(j.getString("content"));
            article.setUrl(j.getString("url"));
            article.setOriginalUrl(j.getString("original_url"));
            article.setCanonicalUrl(j.getString("canonical_url"));
            article.setPublishedAt(j.getString("published_at"));
            article.setCrawledAt(j.getString("crawled_at"));
            article.setMatchedKeywords(j.getJSONArray("matched_keywords") == null
                ? "[]" : j.getJSONArray("matched_keywords").toJSONString());
            article.setContentHash(j.getString("content_hash"));
            article.setCrawlTaskId(taskId);

            NewsArticle resolved = articleMapper.selectByCanonicalUrl(article.getCanonicalUrl());
            if (resolved != null)
            {
                String pendingOp = PENDING_EXISTING;
                if (!Objects.equals(article.getContentHash(), resolved.getContentHash()))
                {
                    article.setId(resolved.getId());
                    articleMapper.updateById(article);
                    resolved = article;
                    pendingOp = PENDING_UPDATED;
                }
                relationMapper.upsert(taskId, resolved.getId(), j.getString("operation"), pendingOp);
                continue;
            }

            resolved = articleMapper.selectByContentHash(article.getContentHash());
            if (resolved == null)
            {
                if (article.getContent() == null || article.getContent().isBlank())
                {
                    // One empty detail body must not abort the whole task after
                    // earlier articles were already staged for preview.
                    log.error("News first-sync skipped: taskId={} sourceName={} canonicalUrl={} operation={} reason=missing_content",
                        taskId, article.getSourceName(), article.getCanonicalUrl(), j.getString("operation"));
                    continue;
                }
                articleMapper.insert(article);
                resolved = article;
                relationMapper.upsert(taskId, resolved.getId(), j.getString("operation"), PENDING_INSERTED);
            }
            else
            {
                relationMapper.upsert(taskId, resolved.getId(), j.getString("operation"), PENDING_EXISTING);
            }
        }
    }
}
