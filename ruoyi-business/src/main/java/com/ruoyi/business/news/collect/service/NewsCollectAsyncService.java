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
import com.ruoyi.business.knowledge.service.KnowledgeIngestService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Bounded asynchronous news execution; MySQL task state is the user-visible truth. */
@Service
public class NewsCollectAsyncService
{
    private static final Logger log = LoggerFactory.getLogger(NewsCollectAsyncService.class);
    private final NewsCollectMapper mapper; private final NewsArticleMapper articleMapper; private final NewsCollectArticleMapper relationMapper; private final AgentNewsServiceClient client; private final ThreadPoolTaskExecutor executor; private final KnowledgeIngestService knowledgeIngestService;
    private final Set<Long> running = ConcurrentHashMap.newKeySet();
    public NewsCollectAsyncService(NewsCollectMapper mapper, NewsArticleMapper articleMapper, NewsCollectArticleMapper relationMapper, AgentNewsServiceClient client, @Qualifier("newsCollectTaskExecutor") ThreadPoolTaskExecutor executor, KnowledgeIngestService knowledgeIngestService)
    { this.mapper = mapper; this.articleMapper = articleMapper; this.relationMapper = relationMapper; this.client = client; this.executor = executor; this.knowledgeIngestService = knowledgeIngestService; }
    public boolean submit(Long id) { if (!running.add(id)) return false; try { executor.execute(() -> run(id)); return true; } catch (RuntimeException e) { running.remove(id); fail(id, "新闻采集任务队列已满，请稍后重试", null, new MySqlSummary()); return false; } }
    private void run(Long id)
    {
        JSONObject stats = null;
        MySqlSummary mysql = new MySqlSummary();
        try {
            NewsCollect task=mapper.selectNewsCollectById(id); if (task==null || !"1".equals(task.getStatus())) return;
            JSONObject result=client.crawl(task.getSourceName(), Boolean.TRUE.equals(task.getForce()), task.getPublishTimeStart(), task.getPublishTimeEnd()); stats=result.getJSONObject("stats");
            persistArticles(task.getId(), result.getJSONArray("articles"), mysql);
            finish(task, result.getString("crawl_run_id"), stats, mysql);
        } catch (Exception e) {
            log.error("News collect task failed: taskId={}", id, e);
            fail(id, "新闻采集服务处理失败，请稍后重试", stats, mysql);
        } finally { running.remove(id); }
    }
    private void finish(NewsCollect task, String runId, JSONObject stats, MySqlSummary mysql) { NewsCollect u=taskResult(task.getId(), "2", stats, mysql); u.setCompletedTime(new Date());u.setCrawlRunId(runId);u.setErrorMessage("");u.setUpdateBy("system");mapper.updateNewsCollectResult(u); }
    private void fail(Long id, String message, JSONObject stats, MySqlSummary mysql) { NewsCollect u=taskResult(id, "3", stats, mysql);u.setCompletedTime(new Date());u.setErrorMessage(message);u.setUpdateBy("system");mapper.updateNewsCollectResult(u); }
    private NewsCollect taskResult(Long id, String status, JSONObject stats, MySqlSummary mysql) { NewsCollect u=new NewsCollect();u.setId(id);u.setStatus(status);u.setFetchedCount(stats==null?0:stats.getIntValue("fetched"));u.setInsertedCount(stats==null?0:stats.getIntValue("inserted"));u.setUpdatedCount(stats==null?0:stats.getIntValue("updated"));u.setDuplicateCount(stats==null?0:stats.getIntValue("duplicate"));u.setFilteredCount(stats==null?0:stats.getIntValue("filtered"));u.setFailedCount(stats==null?0:stats.getIntValue("failed"));u.setMysqlInsertedCount(mysql.inserted);u.setMysqlUpdatedCount(mysql.updated);u.setMysqlExistingCount(mysql.existing);u.setStatisticsVersion("V2");return u; }
    private void persistArticles(Long taskId, JSONArray items, MySqlSummary mysql) throws Exception
    {
        if (items == null) return;
        for (int i=0;i<items.size();i++) {
            JSONObject j=items.getJSONObject(i); NewsArticle article = new NewsArticle();
            article.setSourceName(j.getString("source_name"));article.setSourceSite(j.getString("source_site"));article.setTitle(j.getString("title"));article.setContent(j.getString("content"));article.setUrl(j.getString("url"));article.setOriginalUrl(j.getString("original_url"));article.setCanonicalUrl(j.getString("canonical_url"));article.setPublishedAt(j.getString("published_at"));article.setCrawledAt(j.getString("crawled_at"));article.setMatchedKeywords(j.getJSONArray("matched_keywords") == null ? "[]" : j.getJSONArray("matched_keywords").toJSONString());article.setContentHash(j.getString("content_hash"));article.setCrawlTaskId(taskId);
            NewsArticle resolved = articleMapper.selectByCanonicalUrl(article.getCanonicalUrl());
            if (resolved != null) {
                String mysqlOperation = "MYSQL_EXISTING";
                if (!Objects.equals(article.getContentHash(), resolved.getContentHash())) { article.setId(resolved.getId()); articleMapper.updateById(article); resolved = article; mysqlOperation = "MYSQL_UPDATED"; mysql.updated++; } else mysql.existing++;
                relationMapper.upsert(taskId,resolved.getId(),j.getString("operation"),mysqlOperation);
                publishToUnifiedKnowledge(resolved);
                continue;
            }
            resolved = articleMapper.selectByContentHash(article.getContentHash());
            if (resolved == null) {
                requireContentForFirstSync(taskId, article, j.getString("operation"));
                articleMapper.insert(article);
                resolved=article;
                mysql.inserted++;
                relationMapper.upsert(taskId,resolved.getId(),j.getString("operation"),"MYSQL_INSERTED");
            } else {
                mysql.existing++;
                relationMapper.upsert(taskId,resolved.getId(),j.getString("operation"),"MYSQL_EXISTING");
            }
            publishToUnifiedKnowledge(resolved);
        }
    }

    private void publishToUnifiedKnowledge(NewsArticle article) throws Exception
    {
        String sourceUrl = firstNonBlank(article.getCanonicalUrl(), article.getOriginalUrl(), article.getUrl());
        knowledgeIngestService.submitCollectedNews(article.getId(), article.getSourceName(), article.getSourceSite(),
            article.getTitle(), article.getContent(), sourceUrl, article.getPublishedAt(), article.getCrawledAt(),
            article.getContentHash(), "news-collector");
    }

    private String firstNonBlank(String... values)
    {
        for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }
    private void requireContentForFirstSync(Long taskId, NewsArticle article, String operation)
    {
        if (article.getContent() != null && !article.getContent().isBlank()) return;
        log.error("News first-sync rejected: taskId={} sourceName={} canonicalUrl={} operation={} reason=missing_content",
                taskId, article.getSourceName(), article.getCanonicalUrl(), operation);
        throw new IllegalStateException("新闻正文缺失，无法首次同步到RuoYi主库");
    }
    static final class MySqlSummary { int inserted; int updated; int existing; }
}
