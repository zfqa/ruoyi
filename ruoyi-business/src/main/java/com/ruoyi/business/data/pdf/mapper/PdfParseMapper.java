package com.ruoyi.business.data.pdf.mapper;

import java.util.List;
import com.ruoyi.business.data.pdf.domain.PdfParse;

/**
 * 文本型PDF正文与规则表格解析 数据层
 * 
 * @author ruoyi
 */
public interface PdfParseMapper
{
    public List<PdfParse> selectPdfParseList(PdfParse pdfParse);

    public PdfParse selectPdfParseById(Long id);

    public int insertPdfParse(PdfParse pdfParse);

    public int updatePdfParse(PdfParse pdfParse);

    public int deletePdfParseById(Long id);

    public int deletePdfParseByIds(Long[] ids);
}
