package com.ruoyi.business.vehicle.domain;

import java.util.Date;
import com.ruoyi.common.core.domain.BaseEntity;

public class VehicleCollectTask extends BaseEntity
{
    private Long id;
    private String sourceCode, brandName, seriesId, seriesName, status, errorMessage;
    private Date startedTime, completedTime;
    private Integer fetchedCount, insertedCount, updatedCount, existingCount, failedCount;
    public Long getId() { return id; } public void setId(Long v) { id=v; }
    public String getSourceCode() { return sourceCode; } public void setSourceCode(String v) { sourceCode=v; }
    public String getBrandName() { return brandName; } public void setBrandName(String v) { brandName=v; }
    public String getSeriesId() { return seriesId; } public void setSeriesId(String v) { seriesId=v; }
    public String getSeriesName() { return seriesName; } public void setSeriesName(String v) { seriesName=v; }
    public String getStatus() { return status; } public void setStatus(String v) { status=v; }
    public String getErrorMessage() { return errorMessage; } public void setErrorMessage(String v) { errorMessage=v; }
    public Date getStartedTime() { return startedTime; } public void setStartedTime(Date v) { startedTime=v; }
    public Date getCompletedTime() { return completedTime; } public void setCompletedTime(Date v) { completedTime=v; }
    public Integer getFetchedCount() { return fetchedCount; } public void setFetchedCount(Integer v) { fetchedCount=v; }
    public Integer getInsertedCount() { return insertedCount; } public void setInsertedCount(Integer v) { insertedCount=v; }
    public Integer getUpdatedCount() { return updatedCount; } public void setUpdatedCount(Integer v) { updatedCount=v; }
    public Integer getExistingCount() { return existingCount; } public void setExistingCount(Integer v) { existingCount=v; }
    public Integer getFailedCount() { return failedCount; } public void setFailedCount(Integer v) { failedCount=v; }
}
