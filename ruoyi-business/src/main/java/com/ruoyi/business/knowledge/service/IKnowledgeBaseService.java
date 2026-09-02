package com.ruoyi.business.knowledge.service;

import java.util.List;
import com.ruoyi.business.knowledge.domain.KnowledgeBase;

/**
 * 固定文件知识库及来源展示 服务层
 * 
 * @author ruoyi
 */
public interface IKnowledgeBaseService
{
    public List<KnowledgeBase> selectKnowledgeBaseList(KnowledgeBase knowledgeBase);

    public KnowledgeBase selectKnowledgeBaseById(Long id);

    public int insertKnowledgeBase(KnowledgeBase knowledgeBase);

    public int updateKnowledgeBase(KnowledgeBase knowledgeBase);

    public int deleteKnowledgeBaseById(Long id);

    public int deleteKnowledgeBaseByIds(Long[] ids);
}
