package com.ruoyi.business.news.collect.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import com.ruoyi.business.knowledge.domain.KnowledgeIngestTask;
import com.ruoyi.business.knowledge.service.KnowledgeIngestService;
import com.ruoyi.business.news.article.NewsArticle;
import com.ruoyi.business.news.article.mapper.NewsArticleMapper;
import com.ruoyi.business.news.collect.domain.NewsCollect;
import com.ruoyi.business.news.collect.mapper.NewsCollectArticleMapper;
import com.ruoyi.business.news.collect.mapper.NewsCollectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class NewsKnowledgePublishServiceTest
{
    private NewsCollectMapper taskMapper;
    private NewsCollectArticleMapper relationMapper;
    private NewsArticleMapper articleMapper;
    private KnowledgeIngestService knowledgeIngestService;
    private NewsKnowledgePublishService service;

    @BeforeEach
    void setUp()
    {
        taskMapper = mock(NewsCollectMapper.class);
        relationMapper = mock(NewsCollectArticleMapper.class);
        articleMapper = mock(NewsArticleMapper.class);
        knowledgeIngestService = mock(KnowledgeIngestService.class);
        service = new NewsKnowledgePublishService(taskMapper, relationMapper, articleMapper, knowledgeIngestService);
    }

    @Test
    void publishesOneArticleFinalizesMysqlAndReportsWhetherKnowledgeAlreadyExists() throws Exception
    {
        NewsArticle article = article(7L);
        KnowledgeIngestTask ingestTask = new KnowledgeIngestTask(); ingestTask.setId(19L); ingestTask.setStatus("0");
        when(relationMapper.countTaskArticle(3L, 7L)).thenReturn(1);
        when(relationMapper.countKnowledgeStored(7L)).thenReturn(0);
        when(relationMapper.selectMysqlOperation(3L, 7L)).thenReturn(NewsCollectAsyncService.PENDING_INSERTED, NewsKnowledgePublishService.MYSQL_INSERTED);
        when(relationMapper.selectMysqlOperationCounts(3L)).thenReturn(Map.of("inserted", 1, "updated", 0, "existing", 0));
        when(articleMapper.selectById(7L)).thenReturn(article);
        when(knowledgeIngestService.submitCollectedNews(anyLong(), anyString(), anyString(), anyString(),
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(ingestTask);

        Map<String, Object> result = service.publishArticle(3L, 7L, "tester");

        assertFalse((Boolean) result.get("reused"));
        assertEquals(19L, result.get("ingestTaskId"));
        assertEquals(NewsKnowledgePublishService.MYSQL_INSERTED, result.get("mysqlOperation"));
        verify(relationMapper).updateMysqlOperation(3L, 7L, NewsKnowledgePublishService.MYSQL_INSERTED);
        verify(taskMapper).updateMysqlStatistics(any(NewsCollect.class));
    }

    @Test
    void publishesEveryArticleInCompletedCrawlAndReusesExistingKnowledge() throws Exception
    {
        NewsCollect task = new NewsCollect(); task.setId(3L); task.setStatus("2");
        when(taskMapper.selectNewsCollectById(3L)).thenReturn(task);
        when(relationMapper.selectTaskArticleIds(3L)).thenReturn(List.of(7L, 8L));
        when(relationMapper.countTaskArticle(anyLong(), anyLong())).thenReturn(1);
        when(relationMapper.countKnowledgeStored(7L)).thenReturn(0);
        when(relationMapper.countKnowledgeStored(8L)).thenReturn(1);
        when(relationMapper.selectMysqlOperation(eq(3L), eq(7L))).thenReturn(NewsCollectAsyncService.PENDING_INSERTED);
        when(relationMapper.selectMysqlOperation(eq(3L), eq(8L))).thenReturn(NewsCollectAsyncService.PENDING_EXISTING);
        when(relationMapper.selectMysqlOperationCounts(3L)).thenReturn(Map.of("inserted", 1, "updated", 0, "existing", 1));
        when(articleMapper.selectById(7L)).thenReturn(article(7L));
        when(articleMapper.selectById(8L)).thenReturn(article(8L));
        when(knowledgeIngestService.submitCollectedNews(anyLong(), anyString(), anyString(), anyString(),
            anyString(), anyString(), anyString(), anyString(), anyString(), anyString())).thenReturn(new KnowledgeIngestTask());

        Map<String, Object> result = service.publishTask(3L, "tester");

        assertEquals(2, result.get("total"));
        assertEquals(1, result.get("submitted"));
        assertEquals(1, result.get("reused"));
        assertEquals(0, result.get("failed"));
        assertEquals(1, result.get("mysqlInserted"));
        assertEquals(1, result.get("mysqlExisting"));
        assertTrue((Integer) result.get("submitted") > 0);
        verify(taskMapper).updateMysqlStatistics(any(NewsCollect.class));
    }

    @Test
    void mapsPendingOperationsToFinalMysqlStates()
    {
        assertEquals(NewsKnowledgePublishService.MYSQL_INSERTED,
            NewsKnowledgePublishService.toFinalMysqlOperation(NewsCollectAsyncService.PENDING_INSERTED));
        assertEquals(NewsKnowledgePublishService.MYSQL_UPDATED,
            NewsKnowledgePublishService.toFinalMysqlOperation(NewsCollectAsyncService.PENDING_UPDATED));
        assertEquals(NewsKnowledgePublishService.MYSQL_EXISTING,
            NewsKnowledgePublishService.toFinalMysqlOperation(NewsCollectAsyncService.PENDING_EXISTING));
    }

    private NewsArticle article(Long id)
    {
        NewsArticle article = new NewsArticle(); article.setId(id); article.setSourceName("示例来源");
        article.setSourceSite("example.com"); article.setTitle("新闻" + id);
        article.setContent("这是一段长度足够、可以发布到统一知识库并用于知识问答检索的新闻正文内容。");
        article.setCanonicalUrl("https://example.com/news/" + id); article.setPublishedAt("2026-09-14");
        article.setCrawledAt("2026-09-14T12:00:00"); article.setContentHash("hash-" + id);
        return article;
    }
}
