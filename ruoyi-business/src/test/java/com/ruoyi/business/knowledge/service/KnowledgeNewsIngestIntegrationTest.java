package com.ruoyi.business.knowledge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.ruoyi.business.knowledge.domain.KnowledgeBase;
import com.ruoyi.business.knowledge.domain.KnowledgeChunk;
import com.ruoyi.business.knowledge.domain.KnowledgeIngestTask;
import com.ruoyi.business.knowledge.domain.KnowledgeVersion;
import com.ruoyi.business.knowledge.mapper.KnowledgeBaseMapper;
import com.ruoyi.business.report.service.IAiReportService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

class KnowledgeNewsIngestIntegrationTest
{
    @Test
    void ingestsProvidedNewsBodyAndKeepsOriginalUrl()
        throws Exception
    {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        KnowledgeBase source = new KnowledgeBase();
        source.setId(7L); source.setSourceName("行业新闻"); source.setSourceType("NEWS"); source.setEnabled("1");
        AtomicReference<KnowledgeVersion> versionRef = new AtomicReference<>();
        AtomicReference<KnowledgeIngestTask> taskRef = new AtomicReference<>();
        List<KnowledgeChunk> chunks = new ArrayList<>();
        when(mapper.selectKnowledgeBaseById(7L)).thenReturn(source);
        when(mapper.selectVersionByHash(any(), any())).thenReturn(null);
        doAnswer(invocation -> { KnowledgeVersion value=invocation.getArgument(0); value.setId(11L); versionRef.set(value); return 1; }).when(mapper).insertVersion(any());
        doAnswer(invocation -> { KnowledgeIngestTask value=invocation.getArgument(0); value.setId(12L); taskRef.set(value); return 1; }).when(mapper).insertIngestTask(any());
        when(mapper.selectVersionById(11L)).thenAnswer(invocation -> versionRef.get());
        when(mapper.selectIngestTaskById(12L)).thenAnswer(invocation -> taskRef.get());
        when(mapper.selectIngestTaskByVersionId(11L)).thenAnswer(invocation -> taskRef.get());
        doAnswer(invocation -> { KnowledgeChunk value=invocation.getArgument(0); value.setId((long)chunks.size()+1); chunks.add(value); return 1; }).when(mapper).insertChunk(any());
        doAnswer(invocation -> { ((Runnable)invocation.getArgument(0)).run(); return null; }).when(executor).execute(any(Runnable.class));

        KnowledgeIngestService service = new KnowledgeIngestService(mapper,
            new KnowledgeFileStorage("target/knowledge-news-ingest"), mock(IAiReportService.class), executor, "");
        service.setNewsIngestEnabled(true);
        KnowledgeIngestTask task = service.submitNews(7L, "news-v1", "https://example.com/article/1", "行业快讯",
            "2023年Q3企业：比亚迪，车型：海豚，销量达到12万辆。", "tester");

        assertEquals("2", task.getStatus());
        assertEquals("https://example.com/article/1", versionRef.get().getSourceUrl());
        assertTrue(versionRef.get().getStoredPath().endsWith(".txt"));
        assertTrue(chunks.stream().allMatch(c -> "https://example.com/article/1".equals(c.getSourceUrl())));
    }

    @Test
    void ingestsCrawlerJsonWithPerArticleUrlAndMetadata() throws Exception
    {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
        KnowledgeBase source = new KnowledgeBase();
        source.setId(8L); source.setSourceName("车载行业新闻"); source.setSourceType("NEWS"); source.setEnabled("1");
        AtomicReference<KnowledgeVersion> versionRef = new AtomicReference<>();
        AtomicReference<KnowledgeIngestTask> taskRef = new AtomicReference<>();
        List<KnowledgeChunk> chunks = new ArrayList<>();
        when(mapper.selectKnowledgeBaseById(8L)).thenReturn(source);
        when(mapper.selectVersionByHash(any(), any())).thenReturn(null);
        doAnswer(invocation -> { KnowledgeVersion value=invocation.getArgument(0); value.setId(21L); versionRef.set(value); return 1; }).when(mapper).insertVersion(any());
        doAnswer(invocation -> { KnowledgeIngestTask value=invocation.getArgument(0); value.setId(22L); taskRef.set(value); return 1; }).when(mapper).insertIngestTask(any());
        when(mapper.selectVersionById(21L)).thenAnswer(invocation -> versionRef.get());
        when(mapper.selectIngestTaskById(22L)).thenAnswer(invocation -> taskRef.get());
        when(mapper.selectIngestTaskByVersionId(21L)).thenAnswer(invocation -> taskRef.get());
        doAnswer(invocation -> { KnowledgeChunk value=invocation.getArgument(0); value.setId((long)chunks.size()+1); chunks.add(value); return 1; }).when(mapper).insertChunk(any());
        doAnswer(invocation -> { ((Runnable)invocation.getArgument(0)).run(); return null; }).when(executor).execute(any(Runnable.class));

        String json = "{\"total\":2,\"items\":["
            + "{\"id\":801,\"source_name\":\"小鹏汽车\",\"source_site\":\"xiaopeng.com\","
            + "\"title\":\"小鹏G6上市\",\"content\":\"小鹏G6超级增程车型正式上市，官方指导价为18.68万元。\","
            + "\"published_at\":\"2026/03/06\",\"canonical_url\":\"https://www.xiaopeng.com/news/801.html\","
            + "\"content_hash\":\"hash-801\"},"
            + "{\"id\":802,\"source_name\":\"比亚迪\",\"source_site\":\"byd.com\","
            + "\"title\":\"新车发布\",\"content\":\"比亚迪发布新车型，车载显示配置和智能座舱能力同步升级。\","
            + "\"published_at\":\"2026/03/07\",\"url\":\"https://www.byd.com/news/802.html\","
            + "\"content_hash\":\"hash-802\"}]}";
        MockMultipartFile file = new MockMultipartFile("file", "crawler.json", "application/json",
            json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        KnowledgeIngestService service = new KnowledgeIngestService(mapper,
            new KnowledgeFileStorage("target/knowledge-news-json"), mock(IAiReportService.class), executor, "");
        service.setNewsIngestEnabled(true);

        KnowledgeIngestTask task = service.submitNewsJson(8L, "crawler-v1", file, "tester");

        assertEquals("2", task.getStatus());
        assertEquals("crawler.json", versionRef.get().getOriginalName());
        assertTrue(chunks.stream().anyMatch(c -> "https://www.xiaopeng.com/news/801.html".equals(c.getSourceUrl())
            && c.getContent().contains("18.68万元") && c.getEvidenceJson().contains("2026/03/06")));
        assertTrue(chunks.stream().anyMatch(c -> "https://www.byd.com/news/802.html".equals(c.getSourceUrl())
            && c.getContent().contains("智能座舱")));
    }
}
