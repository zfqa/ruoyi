package com.ruoyi.business.data.pdf.service;

import java.nio.file.Files;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import static org.springframework.transaction.support.TransactionSynchronization.STATUS_COMMITTED;
import com.ruoyi.business.data.pdf.domain.PdfParse;
import com.ruoyi.business.data.pdf.mapper.PdfParseMapper;

/** 原任务重试：只允许失败任务通过一次 CAS 状态切换重新进入后台解析。 */
@Service
public class PdfParseRetryService
{
    private final PdfParseMapper mapper;
    private final DocumentPrivateStorage storage;
    private final PdfDocumentMigrationService migrationService;
    private final PdfParseAsyncService asyncService;

    public PdfParseRetryService(PdfParseMapper mapper, DocumentPrivateStorage storage, PdfDocumentMigrationService migrationService,
            PdfParseAsyncService asyncService)
    {
        this.mapper = mapper; this.storage = storage; this.migrationService = migrationService; this.asyncService = asyncService;
    }

    @Transactional(rollbackFor = Exception.class)
    public PdfParse retry(Long taskId, String username)
    {
        PdfParse task = mapper.selectPdfParseById(taskId);
        if (task == null) throw new IllegalArgumentException("文档解析任务不存在");
        if (!"3".equals(task.getStatus())) throw new IllegalStateException("仅允许重新解析失败任务");
        try
        {
            task = migrationService.migrateIfNeeded(task);
            if (!Files.isRegularFile(storage.resolveForRead(task.getStoredFilePath()))) throw new IllegalStateException("原始文档不存在，无法重新解析");
        }
        catch (IllegalStateException e) { throw e; }
        catch (Exception e) { throw new IllegalStateException("原始文档不存在，无法重新解析"); }

        if (!asyncService.reserve(taskId)) throw new IllegalStateException("解析任务已在执行，请勿重复提交");
        try
        {
            if (mapper.retryPdfParse(taskId, username == null ? "" : username) != 1)
                throw new IllegalStateException("任务状态已变化，请刷新后重试");
            submitAfterCommit(taskId);
        }
        catch (RuntimeException e)
        {
            asyncService.releaseReservation(taskId);
            throw e;
        }
        task.setStatus("1"); task.setErrorMessage(""); task.setCompletedTime(null);
        return task;
    }

    private void submitAfterCommit(Long taskId)
    {
        if (TransactionSynchronizationManager.isSynchronizationActive())
        {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
            {
                @Override public void afterCommit() { asyncService.submitReserved(taskId); }
                @Override public void afterCompletion(int status)
                {
                    if (status != STATUS_COMMITTED) asyncService.releaseReservation(taskId);
                }
            });
        }
        else if (!asyncService.submitReserved(taskId))
            throw new IllegalStateException("解析任务已在执行，请勿重复提交");
    }
}

