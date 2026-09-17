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

    /** Source names retained by historical tasks, including sources since disabled in YAML. */
    public List<String> selectHistoricalSourceNames();

    public NewsCollect selectNewsCollectById(Long id);

    public int insertNewsCollect(NewsCollect newsCollect);

    public int updateNewsCollect(NewsCollect newsCollect);

    public int deleteNewsCollectById(Long id);

    public int deleteNewsCollectByIds(Long[] ids);
    public int updateNewsCollectResult(NewsCollect task);
    public int updateMysqlStatistics(NewsCollect task);

    /** Refresh crawl-side counts after task article links change. */
    public int updateCrawlStatistics(NewsCollect task);

    public int markInterruptedRunningTasks();
}
