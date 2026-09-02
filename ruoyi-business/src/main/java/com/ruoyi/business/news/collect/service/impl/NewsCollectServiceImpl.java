package com.ruoyi.business.news.collect.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.business.news.collect.domain.NewsCollect;
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

    @Override
    public List<NewsCollect> selectNewsCollectList(NewsCollect newsCollect)
    {
        return newsCollectMapper.selectNewsCollectList(newsCollect);
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
        return newsCollectMapper.deleteNewsCollectById(id);
    }

    @Override
    public int deleteNewsCollectByIds(Long[] ids)
    {
        return newsCollectMapper.deleteNewsCollectByIds(ids);
    }
}
