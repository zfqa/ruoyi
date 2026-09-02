package com.ruoyi.business.knowledge.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.business.knowledge.domain.KnowledgeBase;
import com.ruoyi.business.knowledge.mapper.KnowledgeBaseMapper;
import com.ruoyi.business.knowledge.service.IKnowledgeBaseService;

/**
 * 固定文件知识库及来源展示 服务实现
 * 
 * @author ruoyi
 */
@Service
public class KnowledgeBaseServiceImpl implements IKnowledgeBaseService
{
    @Autowired
    private KnowledgeBaseMapper knowledgeBaseMapper;

    @Override
    public List<KnowledgeBase> selectKnowledgeBaseList(KnowledgeBase knowledgeBase)
    {
        return knowledgeBaseMapper.selectKnowledgeBaseList(knowledgeBase);
    }

    @Override
    public KnowledgeBase selectKnowledgeBaseById(Long id)
    {
        return knowledgeBaseMapper.selectKnowledgeBaseById(id);
    }

    @Override
    public int insertKnowledgeBase(KnowledgeBase knowledgeBase)
    {
        return knowledgeBaseMapper.insertKnowledgeBase(knowledgeBase);
    }

    @Override
    public int updateKnowledgeBase(KnowledgeBase knowledgeBase)
    {
        return knowledgeBaseMapper.updateKnowledgeBase(knowledgeBase);
    }

    @Override
    public int deleteKnowledgeBaseById(Long id)
    {
        return knowledgeBaseMapper.deleteKnowledgeBaseById(id);
    }

    @Override
    public int deleteKnowledgeBaseByIds(Long[] ids)
    {
        return knowledgeBaseMapper.deleteKnowledgeBaseByIds(ids);
    }
}
