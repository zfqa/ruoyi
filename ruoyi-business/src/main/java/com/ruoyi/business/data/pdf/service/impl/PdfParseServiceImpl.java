package com.ruoyi.business.data.pdf.service.impl;

import java.util.Date;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;
import com.ruoyi.business.data.pdf.domain.PdfParse;
import com.ruoyi.business.data.pdf.mapper.PdfParseMapper;
import com.ruoyi.business.data.pdf.service.IPdfParseService;
import com.ruoyi.business.data.pdf.service.PdfParseAsyncService;
import com.ruoyi.business.data.pdf.service.DocumentPrivateStorage;
import com.ruoyi.business.data.pdf.service.PdfParseLifecycleService;
import com.ruoyi.common.utils.SecurityUtils;

/** PDF/PPTX 任务管理：只保存文件并创建任务；耗时解析由独立异步服务执行。 */
@Service
public class PdfParseServiceImpl implements IPdfParseService
{
    @Autowired private PdfParseMapper pdfParseMapper;
    @Autowired private PdfParseAsyncService pdfParseAsyncService;
    @Autowired private DocumentPrivateStorage documentStorage;
    @Autowired private PdfParseLifecycleService pdfParseLifecycleService;

    @Override public List<PdfParse> selectPdfParseList(PdfParse pdfParse) { return pdfParseMapper.selectPdfParseList(pdfParse); }
    @Override public PdfParse selectPdfParseById(Long id) { return pdfParseMapper.selectPdfParseById(id); }
    @Override public int insertPdfParse(PdfParse pdfParse) { return pdfParseMapper.insertPdfParse(pdfParse); }
    /**
     * 普通编辑仅允许修改人工业务字段，解析及知识库工作流字段只能由对应后台流程更新。
     */
    @Override
    public int updatePdfParse(PdfParse pdfParse)
    {
        if (pdfParse == null || pdfParse.getId() == null) throw new IllegalArgumentException("PDF解析任务不存在");
        PdfParse editable = new PdfParse();
        editable.setId(pdfParse.getId());
        editable.setTaskName(pdfParse.getTaskName());
        editable.setRemark(pdfParse.getRemark());
        editable.setUpdateBy(currentUsername());
        return pdfParseMapper.updatePdfParse(editable);
    }

    /** 事务提交后才投递后台任务，避免工作线程读取未提交记录。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public PdfParse parseDocument(MultipartFile file, String taskName)
    {
        validateUpload(file);
        String originalFileName = originalFileName(file);
        String storedFilePath;
        try { storedFilePath = documentStorage.store(file, originalFileName).reference(); }
        catch (Exception e) { throw new IllegalArgumentException("文件保存失败，请检查文件后重试"); }

        PdfParse task = new PdfParse();
        task.setTaskName(hasText(taskName) ? taskName.trim() : originalFileName);
        task.setOriginalFileName(originalFileName);
        task.setStoredFilePath(storedFilePath);
        task.setStatus("1");
        task.setEntityCount(0);
        task.setErrorMessage("");
        task.setCreateBy(currentUsername());
        task.setCreateTime(new Date());
        pdfParseMapper.insertPdfParse(task);
        submitAfterCommit(task.getId());
        return task;
    }

    @Override public int deletePdfParseById(Long id) { return pdfParseLifecycleService.deleteUnpublished(id); }
    @Override public int deletePdfParseByIds(Long[] ids) { return pdfParseLifecycleService.deleteUnpublished(ids); }

    private void submitAfterCommit(Long taskId)
    {
        if (TransactionSynchronizationManager.isSynchronizationActive())
        {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
            {
                @Override public void afterCommit() { pdfParseAsyncService.submit(taskId); }
            });
        }
        else pdfParseAsyncService.submit(taskId);
    }

    private void validateUpload(MultipartFile file)
    {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("请选择非空的 PDF 或 PPTX 文件");
        String filename = originalFileName(file).toLowerCase();
        if (!filename.endsWith(".pdf") && !filename.endsWith(".pptx")) throw new IllegalArgumentException("仅支持 PDF 或 PPTX 文件");
    }

    private String originalFileName(MultipartFile file)
    {
        String filename = file.getOriginalFilename();
        if (filename == null) return "document";
        int separator = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));
        return separator >= 0 ? filename.substring(separator + 1) : filename;
    }

    private String currentUsername()
    {
        try { return SecurityUtils.getUsername(); }
        catch (RuntimeException e) { return ""; }
    }

    private boolean hasText(String value) { return value != null && !value.trim().isEmpty(); }
}
