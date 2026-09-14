package com.ruoyi.business.data.pdf.service;

import java.util.List;
import com.ruoyi.business.data.pdf.domain.PdfParse;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文本型PDF正文与规则表格解析 服务层
 * 
 * @author ruoyi
 */
public interface IPdfParseService
{
    public List<PdfParse> selectPdfParseList(PdfParse pdfParse);

    public PdfParse selectPdfParseById(Long id);

    public int insertPdfParse(PdfParse pdfParse);

    public int updatePdfParse(PdfParse pdfParse);

    /** 保存原文件、调用 agent-service 解析并持久化解析任务。 */
    public PdfParse parseDocument(MultipartFile file, String taskName);

    public int deletePdfParseById(Long id);

    public int deletePdfParseByIds(Long[] ids);
}
