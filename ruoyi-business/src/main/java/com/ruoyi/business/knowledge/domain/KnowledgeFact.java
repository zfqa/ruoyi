package com.ruoyi.business.knowledge.domain;

/** 入库时从表格/正文抽出的指标事实，问答按指标名检索，不依赖切片排名。 */
public class KnowledgeFact
{
    private Long id;
    private Long sourceId;
    private Long versionId;
    private Long chunkId;
    private Integer pageStart;
    private String rowLabel;
    private String colHeader;
    private String metricLabel;
    private String rawValue;
    private String unit;
    private Integer factYear;
    private String originalName;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public Long getVersionId() { return versionId; }
    public void setVersionId(Long versionId) { this.versionId = versionId; }
    public Long getChunkId() { return chunkId; }
    public void setChunkId(Long chunkId) { this.chunkId = chunkId; }
    public Integer getPageStart() { return pageStart; }
    public void setPageStart(Integer pageStart) { this.pageStart = pageStart; }
    public String getRowLabel() { return rowLabel; }
    public void setRowLabel(String rowLabel) { this.rowLabel = rowLabel; }
    public String getColHeader() { return colHeader; }
    public void setColHeader(String colHeader) { this.colHeader = colHeader; }
    public String getMetricLabel() { return metricLabel; }
    public void setMetricLabel(String metricLabel) { this.metricLabel = metricLabel; }
    public String getRawValue() { return rawValue; }
    public void setRawValue(String rawValue) { this.rawValue = rawValue; }
    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }
    public Integer getFactYear() { return factYear; }
    public void setFactYear(Integer factYear) { this.factYear = factYear; }
    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }
}
