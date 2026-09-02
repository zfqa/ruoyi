package com.ruoyi.business.report.domain;

import java.io.Serial;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * AI分析与报告 实体
 * 
 * @author ruoyi
 */
public class AiReport extends BaseEntity
{
    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @Excel(name = "主键", cellType = Excel.ColumnType.NUMERIC)
    private Long id;

    /** 来源Excel解析任务 */
    @Excel(name = "来源解析任务", cellType = Excel.ColumnType.NUMERIC)
    private Long importTaskId;

    /** 任务名称/文件名称 */
    @Excel(name = "任务名称")
    private String taskName;

    /** 状态（0待处理 1处理中 2成功 3失败） */
    @Excel(name = "状态", readConverterExp = "0=待处理,1=处理中,2=成功,3=失败")
    private String status;

    /** 报告类型 */
    @Excel(name = "报告类型")
    private String reportType;

    /** 生成模式 */
    @Excel(name = "生成模式")
    private String generationMode;

    /** 结构化报告JSON */
    private String reportContent;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getImportTaskId() { return importTaskId; }
    public void setImportTaskId(Long importTaskId) { this.importTaskId = importTaskId; }

    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getReportType() { return reportType; }
    public void setReportType(String reportType) { this.reportType = reportType; }

    public String getGenerationMode() { return generationMode; }
    public void setGenerationMode(String generationMode) { this.generationMode = generationMode; }

    public String getReportContent() { return reportContent; }
    public void setReportContent(String reportContent) { this.reportContent = reportContent; }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("importTaskId", getImportTaskId())
            .append("taskName", getTaskName())
            .append("status", getStatus())
            .append("reportType", getReportType())
            .append("generationMode", getGenerationMode())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .append("remark", getRemark())
            .toString();
    }
}
