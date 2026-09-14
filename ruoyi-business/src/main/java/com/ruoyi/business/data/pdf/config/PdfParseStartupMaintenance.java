package com.ruoyi.business.data.pdf.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import com.ruoyi.business.data.pdf.mapper.PdfParseMapper;
import com.ruoyi.business.data.pdf.service.PdfDocumentMigrationService;

/** 启动时仅做安全迁移与中断任务对账，绝不自动重调 Python/LLM。 */
@Component
public class PdfParseStartupMaintenance implements ApplicationRunner
{
    private static final Logger LOG = LoggerFactory.getLogger(PdfParseStartupMaintenance.class);
    private static final String INTERRUPTED = "解析任务因服务重启或中断未完成，请重新解析。";
    private final PdfDocumentMigrationService migrationService;
    private final PdfParseMapper mapper;

    public PdfParseStartupMaintenance(PdfDocumentMigrationService migrationService, PdfParseMapper mapper)
    {
        this.migrationService = migrationService; this.mapper = mapper;
    }

    @Override
    public void run(ApplicationArguments args)
    {
        migrationService.migrateLegacyDocuments();
        int reconciled = mapper.markInterruptedProcessingTasks(INTERRUPTED, "system");
        if (reconciled > 0) LOG.warn("Reconciled {} interrupted PDF/PPTX parse task(s)", reconciled);
    }
}

