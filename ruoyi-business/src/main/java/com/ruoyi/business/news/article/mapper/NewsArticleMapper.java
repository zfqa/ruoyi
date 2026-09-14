package com.ruoyi.business.news.article.mapper;
import com.ruoyi.business.news.article.NewsArticle;
import java.util.List; import org.apache.ibatis.annotations.Mapper;
@Mapper public interface NewsArticleMapper { NewsArticle selectByCanonicalUrl(String canonicalUrl); NewsArticle selectByContentHash(String contentHash); NewsArticle selectById(Long id); List<NewsArticle> selectList(NewsArticle article); int insert(NewsArticle article); int updateById(NewsArticle article); }


