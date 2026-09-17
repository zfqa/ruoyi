package com.ruoyi.business.news.collect.service.impl;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.business.agent.client.AgentNewsServiceClient;
import com.ruoyi.business.agent.client.AgentServiceClientException;
import com.ruoyi.business.news.article.mapper.NewsArticleMapper;
import com.ruoyi.business.news.collect.domain.NewsCollect;
import com.ruoyi.business.news.collect.mapper.NewsCollectArticleMapper;
import com.ruoyi.business.news.collect.mapper.NewsCollectMapper;
import com.ruoyi.business.news.collect.service.INewsCollectService;

/**
 * 白名单官网新闻抓取 服务实现
 *
 * @author ruoyi
 */
@Service
public class NewsCollectServiceImpl implements INewsCollectService
{
    private static final Logger log = LoggerFactory.getLogger(NewsCollectServiceImpl.class);

    @Autowired
    private NewsCollectMapper newsCollectMapper;
    @Autowired
    private NewsCollectArticleMapper newsCollectArticleMapper;
    @Autowired
    private NewsArticleMapper newsArticleMapper;
    @Autowired
    private AgentNewsServiceClient agentNewsServiceClient;

    @Override
    public List<NewsCollect> selectNewsCollectList(NewsCollect newsCollect)
    {
        return newsCollectMapper.selectNewsCollectList(newsCollect);
    }

    @Override
    public List<String> selectHistoricalSourceNames()
    {
        return newsCollectMapper.selectHistoricalSourceNames();
    }

    @Override
    public NewsCollect selectNewsCollectById(Long id)
    {
        return newsCollectMapper.selectNewsCollectById(id);
    }

    @Override
    public int insertNewsCollect(NewsCollect newsCollect)
    {
        return newsCollectMapper.insertNewsCollect(newsCollect);
    }

    @Override
    public int updateNewsCollect(NewsCollect newsCollect)
    {
        return newsCollectMapper.updateNewsCollect(newsCollect);
    }

    @Override
    public int deleteNewsCollectById(Long id)
    {
        return deleteNewsCollectByIds(new Long[] { id });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteNewsCollectByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        for (Long id : ids)
        {
            NewsCollect task = newsCollectMapper.selectNewsCollectById(id);
            if (task != null && "1".equals(task.getStatus()))
            {
                throw new ServiceException("存在正在采集的新闻任务，不能删除");
            }
        }
        List<Long> articleIds = newsCollectArticleMapper.selectArticleIdsByTaskIds(ids);
        // Articles are business master data.  Only remove this task's
        // traceability links before deleting the task record itself.
        newsCollectArticleMapper.deleteByTaskIds(ids);
        int deleted = newsCollectMapper.deleteNewsCollectByIds(ids);
        scheduleSqliteSync(articleIds);
        return deleted;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Map<String, Object> deleteTaskArticles(Long taskId, List<Long> articleIds, String username)
    {
        if (taskId == null) throw new ServiceException("任务不存在");
        if (articleIds == null || articleIds.isEmpty()) throw new ServiceException("请选择要移除的新闻");
        NewsCollect task = newsCollectMapper.selectNewsCollectById(taskId);
        if (task == null) throw new ServiceException("新闻采集任务不存在");
        if ("1".equals(task.getStatus())) throw new ServiceException("任务采集中，不能移除新闻");

        int deleted = newsCollectArticleMapper.deleteSelected(taskId, articleIds);
        List<Long> remaining = newsCollectArticleMapper.selectTaskArticleIds(taskId);
        boolean taskDeleted = remaining == null || remaining.isEmpty();
        if (taskDeleted)
        {
            newsCollectMapper.deleteNewsCollectById(taskId);
        }
        else
        {
            refreshDisplayStatistics(taskId, username);
        }
        scheduleSqliteSync(articleIds);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("deleted", deleted);
        result.put("remaining", taskDeleted ? 0 : remaining.size());
        result.put("taskDeleted", taskDeleted);
        result.put("taskId", taskId);
        return result;
    }

    private void scheduleSqliteSync(List<Long> articleIds)
    {
        if (articleIds == null || articleIds.isEmpty()) return;
        List<Long> snapshot = new ArrayList<>(articleIds);
        if (TransactionSynchronizationManager.isSynchronizationActive())
        {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
            {
                @Override
                public void afterCommit()
                {
                    syncSqliteForOrphanArticles(snapshot);
                }
            });
            return;
        }
        syncSqliteForOrphanArticles(snapshot);
    }

    /**
     * After task-list cleanup, drop staging SQLite rows for articles that are
     * no longer linked to any crawl task, so the next crawl will not treat
     * them as URL/content duplicates.
     */
    private void syncSqliteForOrphanArticles(List<Long> articleIds)
    {
        if (articleIds == null || articleIds.isEmpty()) return;
        Set<Long> uniqueIds = new LinkedHashSet<>();
        for (Long id : articleIds)
        {
            if (id != null) uniqueIds.add(id);
        }
        if (uniqueIds.isEmpty()) return;

        List<Long> orphanIds = new ArrayList<>();
        for (Long articleId : uniqueIds)
        {
            if (newsCollectArticleMapper.countLinksByArticleId(articleId) == 0)
            {
                orphanIds.add(articleId);
            }
        }
        if (orphanIds.isEmpty()) return;

        List<String> urls = newsArticleMapper.selectCanonicalUrlsByIds(orphanIds);
        if (urls == null || urls.isEmpty()) return;
        List<String> cleaned = new ArrayList<>();
        for (String url : urls)
        {
            if (url != null && !url.isBlank()) cleaned.add(url.trim());
        }
        if (cleaned.isEmpty()) return;

        try
        {
            agentNewsServiceClient.deleteByCanonicalUrls(cleaned);
            log.info("SQLite staging synced: removed {} orphan article url(s)", cleaned.size());
        }
        catch (AgentServiceClientException ex)
        {
            log.warn("SQLite sync delete failed for {} urls: {}", cleaned.size(), ex.getMessage());
        }
    }

    /** Recompute crawl-side and main-library counts from remaining task links. */
    private void refreshDisplayStatistics(Long taskId, String username)
    {
        Map<String, Object> crawl = newsCollectArticleMapper.selectCrawlOperationCounts(taskId);
        int inserted = intVal(crawl, "inserted");
        int updated = intVal(crawl, "updated");
        int duplicate = intVal(crawl, "duplicate");
        NewsCollect crawlUpdate = new NewsCollect();
        crawlUpdate.setId(taskId);
        crawlUpdate.setInsertedCount(inserted);
        crawlUpdate.setUpdatedCount(updated);
        crawlUpdate.setDuplicateCount(duplicate);
        // Keep "请求" aligned with remaining detail successes still linked to the task.
        crawlUpdate.setFetchedCount(inserted + updated);
        crawlUpdate.setUpdateBy(username == null || username.isBlank() ? "system" : username);
        newsCollectMapper.updateCrawlStatistics(crawlUpdate);

        Map<String, Object> mysql = newsCollectArticleMapper.selectMysqlOperationCounts(taskId);
        NewsCollect mysqlUpdate = new NewsCollect();
        mysqlUpdate.setId(taskId);
        mysqlUpdate.setMysqlInsertedCount(intVal(mysql, "inserted"));
        mysqlUpdate.setMysqlUpdatedCount(intVal(mysql, "updated"));
        mysqlUpdate.setMysqlExistingCount(intVal(mysql, "existing"));
        mysqlUpdate.setStatisticsVersion("V2");
        mysqlUpdate.setUpdateBy(username == null || username.isBlank() ? "system" : username);
        newsCollectMapper.updateMysqlStatistics(mysqlUpdate);
    }

    private int intVal(Map<String, Object> map, String key)
    {
        if (map == null || map.get(key) == null) return 0;
        Object value = map.get(key);
        if (value instanceof Number number) return number.intValue();
        try { return Integer.parseInt(String.valueOf(value)); }
        catch (NumberFormatException ignored) { return 0; }
    }
}
