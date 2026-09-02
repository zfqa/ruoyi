package com.ruoyi.business.data.text.service;

import java.util.List;
import com.ruoyi.business.data.text.domain.TextStruct;

/**
 * 自由文本结构化 服务层
 * 
 * @author ruoyi
 */
public interface ITextStructService
{
    public List<TextStruct> selectTextStructList(TextStruct textStruct);

    public TextStruct selectTextStructById(Long id);

    public int insertTextStruct(TextStruct textStruct);

    public int updateTextStruct(TextStruct textStruct);

    public int deleteTextStructById(Long id);

    public int deleteTextStructByIds(Long[] ids);
}
