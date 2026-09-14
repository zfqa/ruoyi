package com.ruoyi.business.knowledge.domain;

import java.util.List;
import com.fasterxml.jackson.annotation.JsonIgnore;

public class KnowledgeChunk
{
    private Long id;
    private Long sourceId;
    private Long versionId;
    private Integer chunkNo;
    private String titlePath;
    private String content;
    private String sourceSnippet;
    private Integer pageStart;
    private Integer pageEnd;
    private String sourceUrl;
    private Long reportId;
    private String metricId;
    private String evidenceJson;
    private String contentSha256;
    private Integer tokenCount;
    private String sourceName;
    private String originalName;
    private String sourceType;
    private String versionNo;
    private Double score;
    /**
     * 仅用于运行时保存被合并指标的真实数据库切片。引用定位必须落到其中一个原始切片，
     * 不能使用合并文本中的虚拟偏移量。
     */
    @JsonIgnore
    private List<KnowledgeChunk> sourceFragments;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public Long getVersionId() { return versionId; }
    public void setVersionId(Long versionId) { this.versionId = versionId; }
    public Integer getChunkNo() { return chunkNo; }
    public void setChunkNo(Integer chunkNo) { this.chunkNo = chunkNo; }
    public String getTitlePath() { return titlePath; }
    public void setTitlePath(String titlePath) { this.titlePath = titlePath; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public String getSourceSnippet() { return sourceSnippet; }
    public void setSourceSnippet(String sourceSnippet) { this.sourceSnippet = sourceSnippet; }
    public Integer getPageStart() { return pageStart; }
    public void setPageStart(Integer pageStart) { this.pageStart = pageStart; }
    public Integer getPageEnd() { return pageEnd; }
    public void setPageEnd(Integer pageEnd) { this.pageEnd = pageEnd; }
    public String getSourceUrl() { return sourceUrl; }
    public void setSourceUrl(String sourceUrl) { this.sourceUrl = sourceUrl; }
    public Long getReportId() { return reportId; }
    public void setReportId(Long reportId) { this.reportId = reportId; }
    public String getMetricId() { return metricId; }
    public void setMetricId(String metricId) { this.metricId = metricId; }
    public String getEvidenceJson() { return evidenceJson; }
    public void setEvidenceJson(String evidenceJson) { this.evidenceJson = evidenceJson; }
    public String getContentSha256() { return contentSha256; }
    public void setContentSha256(String contentSha256) { this.contentSha256 = contentSha256; }
    public Integer getTokenCount() { return tokenCount; }
    public void setTokenCount(Integer tokenCount) { this.tokenCount = tokenCount; }
    public String getSourceName() { return sourceName; }
    public void setSourceName(String sourceName) { this.sourceName = sourceName; }
    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }
    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }
    public String getVersionNo() { return versionNo; }
    public void setVersionNo(String versionNo) { this.versionNo = versionNo; }
    public Double getScore() { return score; }
    public void setScore(Double score) { this.score = score; }
    public List<KnowledgeChunk> getSourceFragments() { return sourceFragments; }
    public void setSourceFragments(List<KnowledgeChunk> sourceFragments) { this.sourceFragments = sourceFragments; }
}
