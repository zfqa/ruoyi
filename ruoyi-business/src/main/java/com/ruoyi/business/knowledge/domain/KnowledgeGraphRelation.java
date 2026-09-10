package com.ruoyi.business.knowledge.domain;

public class KnowledgeGraphRelation
{
    private Long id;
    private Long fromEntityId;
    private Long toEntityId;
    private String relationType;
    private Long sourceId;
    private Long versionId;
    private Long chunkId;
    private String period;
    private String dataType;
    private String evidenceSnippet;
    private Integer evidenceStart;
    private Integer evidenceEnd;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getFromEntityId() { return fromEntityId; }
    public void setFromEntityId(Long fromEntityId) { this.fromEntityId = fromEntityId; }
    public Long getToEntityId() { return toEntityId; }
    public void setToEntityId(Long toEntityId) { this.toEntityId = toEntityId; }
    public String getRelationType() { return relationType; }
    public void setRelationType(String relationType) { this.relationType = relationType; }
    public Long getSourceId() { return sourceId; }
    public void setSourceId(Long sourceId) { this.sourceId = sourceId; }
    public Long getVersionId() { return versionId; }
    public void setVersionId(Long versionId) { this.versionId = versionId; }
    public Long getChunkId() { return chunkId; }
    public void setChunkId(Long chunkId) { this.chunkId = chunkId; }
    public String getPeriod() { return period; }
    public void setPeriod(String period) { this.period = period; }
    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }
    public String getEvidenceSnippet() { return evidenceSnippet; }
    public void setEvidenceSnippet(String evidenceSnippet) { this.evidenceSnippet = evidenceSnippet; }
    public Integer getEvidenceStart() { return evidenceStart; }
    public void setEvidenceStart(Integer evidenceStart) { this.evidenceStart = evidenceStart; }
    public Integer getEvidenceEnd() { return evidenceEnd; }
    public void setEvidenceEnd(Integer evidenceEnd) { this.evidenceEnd = evidenceEnd; }
}
