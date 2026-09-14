package com.ruoyi.business.data.pdf.domain;

import java.io.Serial;
import java.util.Date;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 文本型PDF正文与规则表格解析 实体
 * 
 * @author ruoyi
 */
public class PdfParse extends BaseEntity
{
    @Serial
    private static final long serialVersionUID = 1L;

    /** 主键 */
    @Excel(name = "主键", cellType = Excel.ColumnType.NUMERIC)
    private Long id;

    /** 任务名称/文件名称 */
    @Excel(name = "任务名称")
    private String taskName;

    /** 状态（0待处理 1处理中 2成功 3失败） */
    @Excel(name = "状态", readConverterExp = "0=待处理,1=处理中,2=成功,3=失败")
    private String status;

    /** 原始文件名 */
    private String originalFileName;

    /** 若依文件服务保存路径 */
    private String storedFilePath;

    /** 解析结果全文 JSON */
    private String resultJson;

    /** 原始正文（预留字段） */
    private String sourceText;

    /** 解析出的实体数量 */
    private Integer entityCount;

    /** Python 返回的模型标识（若提供） */
    private String llmModel;

    /** 解析完成时间 */
    private Date completedTime;

    /** 对用户安全的失败摘要 */
    private String errorMessage;

    /** 以下字段由知识库表查询投影得出，不写入 business_data_pdf。 */
    private String knowledgeStatus;
    private Long knowledgeIngestTaskId;
    private Long knowledgeVersionId;
    private Integer knowledgeProgress;
    private String knowledgeStage;
    private String knowledgeErrorMessage;
    private Date knowledgeFinishedTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getOriginalFileName() { return originalFileName; }
    public void setOriginalFileName(String originalFileName) { this.originalFileName = originalFileName; }

    public String getStoredFilePath() { return storedFilePath; }
    public void setStoredFilePath(String storedFilePath) { this.storedFilePath = storedFilePath; }

    public String getResultJson() { return resultJson; }
    public void setResultJson(String resultJson) { this.resultJson = resultJson; }

    public String getSourceText() { return sourceText; }
    public void setSourceText(String sourceText) { this.sourceText = sourceText; }

    public Integer getEntityCount() { return entityCount; }
    public void setEntityCount(Integer entityCount) { this.entityCount = entityCount; }

    public String getLlmModel() { return llmModel; }
    public void setLlmModel(String llmModel) { this.llmModel = llmModel; }

    public Date getCompletedTime() { return completedTime; }
    public void setCompletedTime(Date completedTime) { this.completedTime = completedTime; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getKnowledgeStatus() { return knowledgeStatus; }
    public void setKnowledgeStatus(String knowledgeStatus) { this.knowledgeStatus = knowledgeStatus; }
    public Long getKnowledgeIngestTaskId() { return knowledgeIngestTaskId; }
    public void setKnowledgeIngestTaskId(Long knowledgeIngestTaskId) { this.knowledgeIngestTaskId = knowledgeIngestTaskId; }
    public Long getKnowledgeVersionId() { return knowledgeVersionId; }
    public void setKnowledgeVersionId(Long knowledgeVersionId) { this.knowledgeVersionId = knowledgeVersionId; }
    public Integer getKnowledgeProgress() { return knowledgeProgress; }
    public void setKnowledgeProgress(Integer knowledgeProgress) { this.knowledgeProgress = knowledgeProgress; }
    public String getKnowledgeStage() { return knowledgeStage; }
    public void setKnowledgeStage(String knowledgeStage) { this.knowledgeStage = knowledgeStage; }
    public String getKnowledgeErrorMessage() { return knowledgeErrorMessage; }
    public void setKnowledgeErrorMessage(String knowledgeErrorMessage) { this.knowledgeErrorMessage = knowledgeErrorMessage; }
    public Date getKnowledgeFinishedTime() { return knowledgeFinishedTime; }
    public void setKnowledgeFinishedTime(Date knowledgeFinishedTime) { this.knowledgeFinishedTime = knowledgeFinishedTime; }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("taskName", getTaskName())
            .append("status", getStatus())
            .append("originalFileName", getOriginalFileName())
            .append("storedFilePath", getStoredFilePath())
            .append("resultJson", getResultJson())
            .append("sourceText", getSourceText())
            .append("entityCount", getEntityCount())
            .append("llmModel", getLlmModel())
            .append("completedTime", getCompletedTime())
            .append("errorMessage", getErrorMessage())
            .append("knowledgeStatus", getKnowledgeStatus())
            .append("knowledgeIngestTaskId", getKnowledgeIngestTaskId())
            .append("knowledgeVersionId", getKnowledgeVersionId())
            .append("knowledgeProgress", getKnowledgeProgress())
            .append("knowledgeStage", getKnowledgeStage())
            .append("knowledgeErrorMessage", getKnowledgeErrorMessage())
            .append("knowledgeFinishedTime", getKnowledgeFinishedTime())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .append("remark", getRemark())
            .toString();
    }
}
