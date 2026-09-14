package com.ruoyi.business.knowledge.service.impl;

import java.util.List;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
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
    public List<KnowledgeBase> selectAuthorizedKnowledgeBaseList(KnowledgeBase knowledgeBase, List<Long> roleIds, boolean admin)
    {
        return knowledgeBaseMapper.selectAuthorizedKnowledgeBaseList(knowledgeBase,
            roleIds == null ? List.of() : roleIds, admin);
    }

    @Override
    public KnowledgeBase selectKnowledgeBaseById(Long id)
    {
        return knowledgeBaseMapper.selectKnowledgeBaseById(id);
    }

    @Override
    public KnowledgeBase selectAuthorizedKnowledgeBaseById(Long id, List<Long> roleIds, boolean admin)
    {
        return knowledgeBaseMapper.selectAuthorizedKnowledgeBaseById(id, roleIds == null ? List.of() : roleIds, admin);
    }

    @Override
    public int insertKnowledgeBase(KnowledgeBase knowledgeBase)
    {
        normalizeAndValidate(knowledgeBase, null);
        return knowledgeBaseMapper.insertKnowledgeBase(knowledgeBase);
    }

    @Override
    public int updateKnowledgeBase(KnowledgeBase knowledgeBase)
    {
        if (knowledgeBase.getId() == null) throw new IllegalArgumentException("资料ID不能为空");
        KnowledgeBase existing = knowledgeBaseMapper.selectKnowledgeBaseById(knowledgeBase.getId());
        if (existing == null) throw new IllegalArgumentException("知识库资料不存在");
        normalizeAndValidate(knowledgeBase, existing);
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

    private void normalizeAndValidate(KnowledgeBase value, KnowledgeBase existing)
    {
        if (value == null) throw new IllegalArgumentException("资料信息不能为空");
        value.setSourceCode(text(value.getSourceCode()));
        value.setSourceName(text(value.getSourceName()));
        value.setSourceType(text(value.getSourceType()).toUpperCase(Locale.ROOT));
        value.setConfidentiality(text(value.getConfidentiality()).toUpperCase(Locale.ROOT));
        value.setAllowedPurpose(text(value.getAllowedPurpose()));
        value.setAllowedRoleIds(normalizeRoleIds(value.getAllowedRoleIds()));
        value.setEnabled(text(value.getEnabled()));

        if (!value.getSourceCode().matches("[A-Za-z0-9._-]{1,100}"))
            throw new IllegalArgumentException("资料编码仅允许1-100位字母、数字、点、下划线和横线");
        if (value.getSourceName().isEmpty() || value.getSourceName().length() > 255)
            throw new IllegalArgumentException("资料名称不能为空且不能超过255个字符");
        if (!Set.of("PDF", "NEWS", "POLICY", "REPORT").contains(value.getSourceType()))
            throw new IllegalArgumentException("来源类型仅支持PDF、NEWS、POLICY或REPORT");
        if (!Set.of("PUBLIC", "INTERNAL", "RESTRICTED").contains(value.getConfidentiality()))
            throw new IllegalArgumentException("密级仅支持PUBLIC、INTERNAL或RESTRICTED");
        if (value.getAllowedPurpose().isEmpty() || value.getAllowedPurpose().length() > 500)
            throw new IllegalArgumentException("允许使用范围不能为空且不能超过500个字符");
        if (!Set.of("0", "1").contains(value.getEnabled()))
            throw new IllegalArgumentException("启用状态无效");

        if (existing != null)
        {
            if (existing.getCurrentVersionId() != null && !value.getSourceType().equalsIgnoreCase(existing.getSourceType()))
                throw new IllegalArgumentException("资料已有入库版本，不能修改来源类型");
            // 当前版本和处理状态只允许由入库流程维护，不能通过编辑接口篡改。
            value.setCurrentVersionId(existing.getCurrentVersionId());
            value.setStatus(existing.getStatus());
        }
    }

    private String normalizeRoleIds(String roleIds)
    {
        String value = text(roleIds);
        if (value.isEmpty()) return "";
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String item : value.split(","))
        {
            String roleId = item.trim();
            if (!roleId.matches("[1-9]\\d*")) throw new IllegalArgumentException("允许角色ID必须是逗号分隔的正整数");
            normalized.add(roleId);
        }
        return String.join(",", normalized);
    }

    private String text(String value)
    {
        return value == null ? "" : value.trim();
    }
}
