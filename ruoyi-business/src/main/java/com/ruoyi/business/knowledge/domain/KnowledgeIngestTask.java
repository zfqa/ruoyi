package com.ruoyi.business.knowledge.domain;

import java.time.LocalDateTime;

public class KnowledgeIngestTask
{
    private Long id;
    private Long sourceId;
    private Long versionId;
    private String status;
    private Integer progress;
    private String currentStage;
    private Integer chunkCount;
    private String errorMessage;
    private LocalDateTime startedTime;
    private LocalDateTime finishedTime;
    private String createBy;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public Long getVersionId() { return versionId; }
    public void setVersionId(Long versionId) { this.versionId = versionId; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public Integer getProgress() { return progress; }
    public void setProgress(Integer progress) { this.progress = progress; }
    public String getCurrentStage() { return currentStage; }
    public void setCurrentStage(String currentStage) { this.currentStage = currentStage; }
    public Integer getChunkCount() { return chunkCount; }
    public void setChunkCount(Integer chunkCount) { this.chunkCount = chunkCount; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public LocalDateTime getStartedTime() { return startedTime; }
    public void setStartedTime(LocalDateTime startedTime) { this.startedTime = startedTime; }
    public LocalDateTime getFinishedTime() { return finishedTime; }
    public void setFinishedTime(LocalDateTime finishedTime) { this.finishedTime = finishedTime; }
    public String getCreateBy() { return createBy; }
    public void setCreateBy(String createBy) { this.createBy = createBy; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
    public LocalDateTime getUpdateTime() { return updateTime; }
    public void setUpdateTime(LocalDateTime updateTime) { this.updateTime = updateTime; }
}
