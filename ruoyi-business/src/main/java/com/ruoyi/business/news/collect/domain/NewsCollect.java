package com.ruoyi.business.news.collect.domain;

import java.io.Serial;
import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;
import com.ruoyi.common.annotation.Excel;
import com.ruoyi.common.core.domain.BaseEntity;

/**
 * 白名单官网新闻抓取 实体
 * 
 * @author ruoyi
 */
public class NewsCollect extends BaseEntity
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
    private String sourceName;
    private String triggerType;
    private java.util.Date startedTime;
    private java.util.Date completedTime;
    private Integer fetchedCount, insertedCount, updatedCount, duplicateCount, filteredCount, failedCount;
    private Integer mysqlInsertedCount, mysqlUpdatedCount, mysqlExistingCount;
    private String crawlRunId, errorMessage;
    private String publishTimeStart, publishTimeEnd, forceFlag;
    private String statisticsVersion;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getTaskName() { return taskName; }
    public void setTaskName(String taskName) { this.taskName = taskName; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getSourceName() { return sourceName; } public void setSourceName(String value) { sourceName = value; }
    public String getTriggerType() { return triggerType; } public void setTriggerType(String value) { triggerType = value; }
    public java.util.Date getStartedTime() { return startedTime; } public void setStartedTime(java.util.Date value) { startedTime = value; }
    public java.util.Date getCompletedTime() { return completedTime; } public void setCompletedTime(java.util.Date value) { completedTime = value; }
    public Integer getFetchedCount() { return fetchedCount; } public void setFetchedCount(Integer value) { fetchedCount = value; }
    public Integer getInsertedCount() { return insertedCount; } public void setInsertedCount(Integer value) { insertedCount = value; }
    public Integer getUpdatedCount() { return updatedCount; } public void setUpdatedCount(Integer value) { updatedCount = value; }
    public Integer getDuplicateCount() { return duplicateCount; } public void setDuplicateCount(Integer value) { duplicateCount = value; }
    public Integer getFilteredCount() { return filteredCount; } public void setFilteredCount(Integer value) { filteredCount = value; }
    public Integer getFailedCount() { return failedCount; } public void setFailedCount(Integer value) { failedCount = value; }
    public Integer getMysqlInsertedCount() { return mysqlInsertedCount; } public void setMysqlInsertedCount(Integer value) { mysqlInsertedCount = value; }
    public Integer getMysqlUpdatedCount() { return mysqlUpdatedCount; } public void setMysqlUpdatedCount(Integer value) { mysqlUpdatedCount = value; }
    public Integer getMysqlExistingCount() { return mysqlExistingCount; } public void setMysqlExistingCount(Integer value) { mysqlExistingCount = value; }
    public String getCrawlRunId() { return crawlRunId; } public void setCrawlRunId(String value) { crawlRunId = value; }
    public String getErrorMessage() { return errorMessage; } public void setErrorMessage(String value) { errorMessage = value; }
    public String getPublishTimeStart() { return publishTimeStart; } public void setPublishTimeStart(String value) { publishTimeStart = value; }
    public String getPublishTimeEnd() { return publishTimeEnd; } public void setPublishTimeEnd(String value) { publishTimeEnd = value; }
    public String getForceFlag() { return forceFlag; } public void setForceFlag(String value) { forceFlag = value; }
    public String getStatisticsVersion() { return statisticsVersion; } public void setStatisticsVersion(String value) { statisticsVersion = value; }
    /** JSON request uses Boolean force; persistence stores the stable 0/1 representation. */
    public Boolean getForce() { return "1".equals(forceFlag); }
    public void setForce(Boolean value) { forceFlag = Boolean.TRUE.equals(value) ? "1" : "0"; }

    @Override
    public String toString() {
        return new ToStringBuilder(this, ToStringStyle.MULTI_LINE_STYLE)
            .append("id", getId())
            .append("taskName", getTaskName())
            .append("status", getStatus())
            .append("createBy", getCreateBy())
            .append("createTime", getCreateTime())
            .append("updateBy", getUpdateBy())
            .append("updateTime", getUpdateTime())
            .append("remark", getRemark())
            .toString();
    }
}
