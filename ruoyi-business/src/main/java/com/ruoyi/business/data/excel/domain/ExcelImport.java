package com.ruoyi.business.data.excel.domain;

import java.io.Serial;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * Excel/CSV导入与字段映射 实体
 * 
 * @author ruoyi
 */
public class ExcelImport extends BaseEntity
{
    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @Excel(name = "主键", cellType = Excel.ColumnType.NUMERIC)
    private Long id;

    /** 任务名称/文件名称 */
    @Excel(name = "任务名称")
    private String taskName;

    /** 原始文件名 */
    @Excel(name = "原始文件名")
    private String fileName;

    /** 上传文件路径 */
    private String filePath;

    /** 工作簿哈希ID */
    @Excel(name = "工作簿ID")
    private String workbookId;

    /** Sheet数量 */
    @Excel(name = "Sheet数量", cellType = Excel.ColumnType.NUMERIC)
    private Integer sheetCount;

    /** 识别表格数量 */
    @Excel(name = "识别表格数量", cellType = Excel.ColumnType.NUMERIC)
    private Integer tableCount;

    /** 预览记录数量 */
    @Excel(name = "预览记录数量", cellType = Excel.ColumnType.NUMERIC)
    private Integer recordCount;

    /** 解析结果JSON */
    private String resultJson;

    /** 状态（0待处理 1处理中 2成功 3失败） */
    @Excel(name = "状态", readConverterExp = "0=待处理,1=处理中,2=成功,3=失败")
    private String status;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }

    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }

    public String getFilePath() { return filePath; }
    public void setFilePath(String filePath) { this.filePath = filePath; }

    public String getWorkbookId() { return workbookId; }
    public void setWorkbookId(String workbookId) { this.workbookId = workbookId; }

    public Integer getSheetCount() { return sheetCount; }
    public void setSheetCount(Integer sheetCount) { this.sheetCount = sheetCount; }

    public Integer getTableCount() { return tableCount; }
    public void setTableCount(Integer tableCount) { this.tableCount = tableCount; }

    public Integer getRecordCount() { return recordCount; }
    public void setRecordCount(Integer recordCount) { this.recordCount = recordCount; }

    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("taskName", getTaskName())
            .append("fileName", getFileName())
            .append("filePath", getFilePath())
            .append("workbookId", getWorkbookId())
            .append("sheetCount", getSheetCount())
            .append("tableCount", getTableCount())
            .append("recordCount", getRecordCount())
            .append("status", getStatus())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .append("remark", getRemark())
            .toString();
    }
}
