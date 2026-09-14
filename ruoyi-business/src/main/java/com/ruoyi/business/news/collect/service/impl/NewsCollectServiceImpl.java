package com.ruoyi.business.news.collect.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.common.exception.ServiceException;
import com.ruoyi.business.news.collect.domain.NewsCollect;
import com.ruoyi.business.news.collect.mapper.NewsCollectArticleMapper;
import com.ruoyi.business.news.collect.mapper.NewsCollectMapper;
import com.ruoyi.business.news.collect.service.INewsCollectService;

/**
 * 白名单官网新闻抓取 服务实现
 * 
 * @author ruoyi
 */
@Service
public class NewsCollectServiceImpl implements INewsCollectService
{
    @Autowired
    private NewsCollectMapper newsCollectMapper;
    @Autowired
    private NewsCollectArticleMapper newsCollectArticleMapper;

    @Override
    public List<NewsCollect> selectNewsCollectList(NewsCollect newsCollect)
    {
        return newsCollectMapper.selectNewsCollectList(newsCollect);
    }

    @Override
    public List<String> selectHistoricalSourceNames()
    {
        return newsCollectMapper.selectHistoricalSourceNames();
    }

    @Override
    public NewsCollect selectNewsCollectById(Long id)
    {
        return newsCollectMapper.selectNewsCollectById(id);
    }

    @Override
    public int insertNewsCollect(NewsCollect newsCollect)
    {
        return newsCollectMapper.insertNewsCollect(newsCollect);
    }

    @Override
    public int updateNewsCollect(NewsCollect newsCollect)
    {
        return newsCollectMapper.updateNewsCollect(newsCollect);
    }

    @Override
    public int deleteNewsCollectById(Long id)
    {
        return deleteNewsCollectByIds(new Long[] { id });
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public int deleteNewsCollectByIds(Long[] ids)
    {
        if (ids == null || ids.length == 0)
        {
            return 0;
        }
        for (Long id : ids)
        {
            NewsCollect task = newsCollectMapper.selectNewsCollectById(id);
            if (task != null && "1".equals(task.getStatus()))
            {
                throw new ServiceException("存在正在采集的新闻任务，不能删除");
            }
        }
        // Articles are business master data.  Only remove this task's
        // traceability links before deleting the task record itself.
        newsCollectArticleMapper.deleteByTaskIds(ids);
        return newsCollectMapper.deleteNewsCollectByIds(ids);
    }
}
