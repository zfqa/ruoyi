package com.ruoyi.business.data.pdf.service;

import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import com.ruoyi.business.data.pdf.domain.PdfParse;
import com.ruoyi.business.data.pdf.mapper.PdfParseMapper;

/** 将旧 /profile 业务文档迁移为 private:// 受控引用。 */
@Service
public class PdfDocumentMigrationService
{
    private static final Logger LOG = LoggerFactory.getLogger(PdfDocumentMigrationService.class);
    private final PdfParseMapper mapper;
    private final DocumentPrivateStorage storage;

    public PdfDocumentMigrationService(PdfParseMapper mapper, DocumentPrivateStorage storage)
    {
        this.mapper = mapper;
        this.storage = storage;
    }

    /** 启动时尽力迁移全部历史业务文档；单条失败只记录日志，不阻断应用启动。 */
    public void migrateLegacyDocuments()
    {
        List<PdfParse> tasks = mapper.selectLegacyProfileDocuments();
        for (PdfParse task : tasks)
        {
            try { migrateIfNeeded(task); }
            catch (Exception e)
            {
                LOG.error("Business document private migration failed taskId={} file={}", task.getId(), task.getOriginalFileName(), e);
            }
        }
    }

    /** 返回迁移后的任务；调用方可继续使用其新的 private:// 受控引用。 */
    public PdfParse migrateIfNeeded(PdfParse task) throws IOException
    {
        if (task == null) throw new IOException("文档解析任务不存在");
        if (storage.isPrivateReference(task.getStoredFilePath())) return task;
        DocumentPrivateStorage.StoredDocument moved = storage.moveLegacyProfileFile(task.getStoredFilePath(), task.getOriginalFileName());
        int updated = mapper.updateStoredFilePath(task.getId(), moved.reference(), "system");
        if (updated != 1)
            throw new IOException("历史文档迁移状态更新失败");
        task.setStoredFilePath(moved.reference());
        LOG.info("Business document migrated to private storage taskId={}", task.getId());
        return task;
    }
}

