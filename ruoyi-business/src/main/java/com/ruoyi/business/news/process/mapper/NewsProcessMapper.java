package com.ruoyi.business.news.process.mapper;

import java.util.List;
import com.ruoyi.business.news.process.domain.NewsProcess;

/**
 * 新闻清洗、分类与事件提取 数据层
 * 
 * @author ruoyi
 */
public interface NewsProcessMapper
{
    public List<NewsProcess> selectNewsProcessList(NewsProcess newsProcess);

    public NewsProcess selectNewsProcessById(Long id);

    public int insertNewsProcess(NewsProcess newsProcess);

    public int updateNewsProcess(NewsProcess newsProcess);

    public int deleteNewsProcessById(Long id);

    public int deleteNewsProcessByIds(Long[] ids);
}
