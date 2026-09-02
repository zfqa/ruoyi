package com.ruoyi.business.news.process.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.business.news.process.domain.NewsProcess;
import com.ruoyi.business.news.process.mapper.NewsProcessMapper;
import com.ruoyi.business.news.process.service.INewsProcessService;

/**
 * 新闻清洗、分类与事件提取 服务实现
 * 
 * @author ruoyi
 */
@Service
public class NewsProcessServiceImpl implements INewsProcessService
{
    @Autowired
    private NewsProcessMapper newsProcessMapper;

    @Override
    public List<NewsProcess> selectNewsProcessList(NewsProcess newsProcess)
    {
        return newsProcessMapper.selectNewsProcessList(newsProcess);
    }

    @Override
    public NewsProcess selectNewsProcessById(Long id)
    {
        return newsProcessMapper.selectNewsProcessById(id);
    }

    @Override
    public int insertNewsProcess(NewsProcess newsProcess)
    {
        return newsProcessMapper.insertNewsProcess(newsProcess);
    }

    @Override
    public int updateNewsProcess(NewsProcess newsProcess)
    {
        return newsProcessMapper.updateNewsProcess(newsProcess);
    }

    @Override
    public int deleteNewsProcessById(Long id)
    {
        return newsProcessMapper.deleteNewsProcessById(id);
    }

    @Override
    public int deleteNewsProcessByIds(Long[] ids)
    {
        return newsProcessMapper.deleteNewsProcessByIds(ids);
    }
}
