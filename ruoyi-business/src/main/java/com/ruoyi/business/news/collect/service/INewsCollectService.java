package com.ruoyi.business.news.collect.service;

import java.util.List;
import java.util.Map;
import com.ruoyi.business.news.collect.domain.NewsCollect;

/**
 * 白名单官网新闻抓取 服务层
 * 
 * @author ruoyi
 */
public interface INewsCollectService
{
    public List<NewsCollect> selectNewsCollectList(NewsCollect newsCollect);

    public List<String> selectHistoricalSourceNames();

    public NewsCollect selectNewsCollectById(Long id);

    public int insertNewsCollect(NewsCollect newsCollect);

    public int updateNewsCollect(NewsCollect newsCollect);

    public int deleteNewsCollectById(Long id);

    public int deleteNewsCollectByIds(Long[] ids);

    /** Remove task-article links; when none remain, delete the task as well. */
    public Map<String, Object> deleteTaskArticles(Long taskId, List<Long> articleIds, String username);
}
