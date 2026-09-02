package com.ruoyi.business.news.process.service;

import java.util.List;
import com.ruoyi.business.news.process.domain.NewsProcess;

/**
 * 新闻清洗、分类与事件提取 服务层
 * 
 * @author ruoyi
 */
public interface INewsProcessService
{
    public List<NewsProcess> selectNewsProcessList(NewsProcess newsProcess);

    public NewsProcess selectNewsProcessById(Long id);

    public int insertNewsProcess(NewsProcess newsProcess);

    public int updateNewsProcess(NewsProcess newsProcess);

    public int deleteNewsProcessById(Long id);

    public int deleteNewsProcessByIds(Long[] ids);
}
