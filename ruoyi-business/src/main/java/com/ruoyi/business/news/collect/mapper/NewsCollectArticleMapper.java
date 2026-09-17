package com.ruoyi.business.news.collect.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface NewsCollectArticleMapper
{
    int upsert(@Param("taskId") Long taskId, @Param("articleId") Long articleId,
        @Param("operation") String operation, @Param("mysqlOperation") String mysqlOperation);

    List<Map<String, Object>> selectTaskArticles(@Param("taskId") Long taskId);

    List<Long> selectTaskArticleIds(@Param("taskId") Long taskId);

    List<Long> selectArticleIdsByTaskIds(@Param("taskIds") Long[] taskIds);

    int countLinksByArticleId(@Param("articleId") Long articleId);

    int countTaskArticle(@Param("taskId") Long taskId, @Param("articleId") Long articleId);

    int countKnowledgeStored(@Param("articleId") Long articleId);

    String selectMysqlOperation(@Param("taskId") Long taskId, @Param("articleId") Long articleId);

    int updateMysqlOperation(@Param("taskId") Long taskId, @Param("articleId") Long articleId,
        @Param("mysqlOperation") String mysqlOperation);

    Map<String, Object> selectMysqlOperationCounts(@Param("taskId") Long taskId);

    Map<String, Object> selectCrawlOperationCounts(@Param("taskId") Long taskId);

    int deleteSelected(@Param("taskId") Long taskId, @Param("articleIds") List<Long> articleIds);

    int deleteByTaskIds(@Param("taskIds") Long[] taskIds);
}
