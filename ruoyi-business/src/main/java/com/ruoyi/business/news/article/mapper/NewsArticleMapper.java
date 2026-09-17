package com.ruoyi.business.news.article.mapper;

import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import com.ruoyi.business.news.article.NewsArticle;

@Mapper
public interface NewsArticleMapper
{
    NewsArticle selectByCanonicalUrl(String canonicalUrl);

    NewsArticle selectByContentHash(String contentHash);

    NewsArticle selectById(Long id);

    List<NewsArticle> selectList(NewsArticle article);

    List<String> selectCanonicalUrlsByIds(@Param("ids") List<Long> ids);

    int insert(NewsArticle article);

    int updateById(NewsArticle article);
}
