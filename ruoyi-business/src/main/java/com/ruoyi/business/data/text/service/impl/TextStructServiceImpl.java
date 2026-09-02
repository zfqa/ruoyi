package com.ruoyi.business.data.text.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.business.data.text.domain.TextStruct;
import com.ruoyi.business.data.text.mapper.TextStructMapper;
import com.ruoyi.business.data.text.service.ITextStructService;

/**
 * 自由文本结构化 服务实现
 * 
 * @author ruoyi
 */
@Service
public class TextStructServiceImpl implements ITextStructService
{
    @Autowired
    private TextStructMapper textStructMapper;

    @Override
    public List<TextStruct> selectTextStructList(TextStruct textStruct)
    {
        return textStructMapper.selectTextStructList(textStruct);
    }

    @Override
    public TextStruct selectTextStructById(Long id)
    {
        return textStructMapper.selectTextStructById(id);
    }

    @Override
    public int insertTextStruct(TextStruct textStruct)
    {
        return textStructMapper.insertTextStruct(textStruct);
    }

    @Override
    public int updateTextStruct(TextStruct textStruct)
    {
        return textStructMapper.updateTextStruct(textStruct);
    }

    @Override
    public int deleteTextStructById(Long id)
    {
        return textStructMapper.deleteTextStructById(id);
    }

    @Override
    public int deleteTextStructByIds(Long[] ids)
    {
        return textStructMapper.deleteTextStructByIds(ids);
    }
}
