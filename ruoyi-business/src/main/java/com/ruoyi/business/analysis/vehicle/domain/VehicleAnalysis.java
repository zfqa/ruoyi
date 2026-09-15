package com.ruoyi.business.analysis.vehicle.domain;

import java.io.Serial;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 整车市场分析-基础统计、排名与趋势 实体
 * 
 * @author ruoyi
 */
public class VehicleAnalysis extends BaseEntity
{
    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @Excel(name = "主键", cellType = Excel.ColumnType.NUMERIC)
    private Long id;

    /** 任务名称/文件名称 */
    @Excel(name = "任务名称")
    private String taskName;

    /** Python分析数据集ID */
    private String datasetId;

    private String fileName;

    private String sourceFilesJson;

    private Long rowCount;

    private Integer sheetCount;

    private Integer issueCount;

    private String parserVersion;

    /** 状态（0待处理 1处理中 2成功 3失败） */
    @Excel(name = "状态", readConverterExp = "0=待处理,1=处理中,2=成功,3=失败")
    private String status;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }

    public String getDatasetId() { return datasetId; }
    public void setDatasetId(String datasetId) { this.datasetId = datasetId; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getSourceFilesJson() { return sourceFilesJson; }
    public void setSourceFilesJson(String sourceFilesJson) { this.sourceFilesJson = sourceFilesJson; }
    public Long getRowCount() { return rowCount; }
    public void setRowCount(Long rowCount) { this.rowCount = rowCount; }
    public Integer getSheetCount() { return sheetCount; }
    public void setSheetCount(Integer sheetCount) { this.sheetCount = sheetCount; }
    public Integer getIssueCount() { return issueCount; }
    public void setIssueCount(Integer issueCount) { this.issueCount = issueCount; }
    public String getParserVersion() { return parserVersion; }
    public void setParserVersion(String parserVersion) { this.parserVersion = parserVersion; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("taskName", getTaskName())
            .append("datasetId", getDatasetId())
            .append("fileName", getFileName())
            .append("rowCount", getRowCount())
            .append("sheetCount", getSheetCount())
            .append("issueCount", getIssueCount())
            .append("status", getStatus())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .append("remark", getRemark())
            .toString();
    }
}
