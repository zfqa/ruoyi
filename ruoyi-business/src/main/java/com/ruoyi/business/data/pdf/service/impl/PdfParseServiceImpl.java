package com.ruoyi.business.data.pdf.service.impl;

import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.ruoyi.business.data.pdf.domain.PdfParse;
import com.ruoyi.business.data.pdf.mapper.PdfParseMapper;
import com.ruoyi.business.data.pdf.service.IPdfParseService;

/**
 * 文本型PDF正文与规则表格解析 服务实现
 * 
 * @author ruoyi
 */
@Service
public class PdfParseServiceImpl implements IPdfParseService
{
    @Autowired
    private PdfParseMapper pdfParseMapper;

    @Override
    public List<PdfParse> selectPdfParseList(PdfParse pdfParse)
    {
        return pdfParseMapper.selectPdfParseList(pdfParse);
    }

    @Override
    public PdfParse selectPdfParseById(Long id)
    {
        return pdfParseMapper.selectPdfParseById(id);
    }

    @Override
    public int insertPdfParse(PdfParse pdfParse)
    {
        return pdfParseMapper.insertPdfParse(pdfParse);
    }

    @Override
    public int updatePdfParse(PdfParse pdfParse)
    {
        return pdfParseMapper.updatePdfParse(pdfParse);
    }

    @Override
    public int deletePdfParseById(Long id)
    {
        return pdfParseMapper.deletePdfParseById(id);
    }

    @Override
    public int deletePdfParseByIds(Long[] ids)
    {
        return pdfParseMapper.deletePdfParseByIds(ids);
    }
}
