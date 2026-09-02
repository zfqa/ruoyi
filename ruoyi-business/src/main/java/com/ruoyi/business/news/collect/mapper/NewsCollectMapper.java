package com.ruoyi.business.news.collect.mapper;

import java.util.List;
import com.ruoyi.business.news.collect.domain.NewsCollect;

/**
 * 白名单官网新闻抓取 数据层
 * 
 * @author ruoyi
 */
public interface NewsCollectMapper
{
    public List<NewsCollect> selectNewsCollectList(NewsCollect newsCollect);

    public NewsCollect selectNewsCollectById(Long id);

    public int insertNewsCollect(NewsCollect newsCollect);

    public int updateNewsCollect(NewsCollect newsCollect);

    public int deleteNewsCollectById(Long id);

    public int deleteNewsCollectByIds(Long[] ids);
}
