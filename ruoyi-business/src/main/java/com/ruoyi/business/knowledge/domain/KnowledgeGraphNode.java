package com.ruoyi.business.knowledge.domain;

public class KnowledgeGraphNode
{
    private Long id;
    private String entityKey;
    private String entityName;
    private String entityType;
    private String aliases;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public String getEntityKey() { return entityKey; }
    public void setEntityKey(String entityKey) { this.entityKey = entityKey; }
    public String getEntityName() { return entityName; }
    public void setEntityName(String entityName) { this.entityName = entityName; }
    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }
    public String getAliases() { return aliases; }
    public void setAliases(String aliases) { this.aliases = aliases; }
}
