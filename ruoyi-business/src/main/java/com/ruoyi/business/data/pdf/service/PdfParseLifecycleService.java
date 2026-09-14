package com.ruoyi.business.data.pdf.service;

import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import com.ruoyi.business.data.pdf.domain.PdfParse;
import com.ruoyi.business.data.pdf.mapper.PdfParseMapper;

/** 文档任务删除的唯一入口：先验证未产生任何 KB 版本，再删除私有原文件。 */
@Service
public class PdfParseLifecycleService
{
    private static final Logger LOG = LoggerFactory.getLogger(PdfParseLifecycleService.class);
    private final PdfParseMapper mapper;
    private final DocumentPrivateStorage storage;
    private final PdfDocumentMigrationService migrationService;

    public PdfParseLifecycleService(PdfParseMapper mapper, DocumentPrivateStorage storage, PdfDocumentMigrationService migrationService)
    {
        this.mapper = mapper;
        this.storage = storage;
        this.migrationService = migrationService;
    }

    @Transactional(rollbackFor = Exception.class)
    public int deleteUnpublished(Long id)
    {
        return deleteUnpublished(new Long[] {id});
    }

    @Transactional(rollbackFor = Exception.class)
    public int deleteUnpublished(Long[] ids)
    {
        if (ids == null || ids.length == 0) return 0;
        Map<Long, PdfParse> tasks = new LinkedHashMap<>();
        for (Long id : ids)
        {
            if (id == null) continue;
            PdfParse task = mapper.selectPdfParseById(id);
            if (task == null) continue;
            if (mapper.countKnowledgeVersionsByPdfTaskId(id) > 0)
                throw new IllegalStateException("该文档已发布到知识库，不能直接删除，请先处理对应知识源。");
            tasks.put(id, task);
        }
        for (PdfParse task : tasks.values())
        {
            try { migrationService.migrateIfNeeded(task); }
            catch (Exception e) { throw new IllegalStateException("原始文档未完成私有迁移，暂不能删除"); }
            if (!storage.isPrivateReference(task.getStoredFilePath()))
                throw new IllegalStateException("原始文档未完成私有迁移，暂不能删除");
        }
        int deleted = 0;
        for (PdfParse task : tasks.values())
        {
            if (mapper.deletePdfParseById(task.getId()) == 1)
            {
                deleted++;
                deleteAfterCommit(task.getStoredFilePath(), task.getId());
            }
        }
        return deleted;
    }

    private void deleteAfterCommit(String storedFilePath, Long taskId)
    {
        Runnable remove = () -> {
            try { storage.deletePrivate(storedFilePath); }
            catch (Exception e) { LOG.error("Private business document deletion failed taskId={}", taskId, e); }
        };
        if (TransactionSynchronizationManager.isSynchronizationActive())
        {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization()
            {
                @Override public void afterCommit() { remove.run(); }
            });
        }
        else remove.run();
    }
}

