package com.ruoyi.business.knowledge.service;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.ruoyi.business.knowledge.mapper.KnowledgeBaseMapper;
import com.ruoyi.business.report.service.IAiReportService;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 新闻抓取本期仅保留接口，默认不得创建版本或发起网络请求。 */
class KnowledgeNewsReservationTest
{
    @Test
    void rejectsNewsIngestBeforeDatabaseOrNetworkAccessWhenFeatureIsDisabled()
    {
        KnowledgeBaseMapper mapper = mock(KnowledgeBaseMapper.class);
        KnowledgeIngestService service = new KnowledgeIngestService(mapper,
            new KnowledgeFileStorage("target/knowledge-news-reservation"), mock(IAiReportService.class),
            mock(ThreadPoolTaskExecutor.class), "example.com");

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> service.submitNews(1L, "v1", "https://example.com/news", "title", "tester"));

        assertTrue(error.getMessage().contains("已预留"));
        verifyNoInteractions(mapper);
    }
}
