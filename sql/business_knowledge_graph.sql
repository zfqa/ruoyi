-- 多源知识图谱：实体和带来源的关系。可重复执行，不修改现有知识切片。
CREATE TABLE IF NOT EXISTS business_kb_entity (
  id bigint(20) NOT NULL AUTO_INCREMENT COMMENT '实体ID',
  entity_key varchar(300) NOT NULL COMMENT '类型+规范名称唯一键',
  entity_name varchar(255) NOT NULL COMMENT '展示名称',
  entity_type varchar(30) NOT NULL COMMENT 'COMPANY/MODEL/SALES/NEWS/FINANCIAL/POLICY/REPORT/DOCUMENT',
  aliases varchar(1000) DEFAULT '' COMMENT '别名',
  create_time datetime DEFAULT NULL,
  update_time datetime DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_business_kb_entity_key (entity_key),
  KEY idx_business_kb_entity_type (entity_type, entity_name)
) ENGINE=InnoDB COMMENT='知识图谱实体';

CREATE TABLE IF NOT EXISTS business_kb_relation (
  id bigint(20) NOT NULL AUTO_INCREMENT COMMENT '关系ID',
  from_entity_id bigint(20) NOT NULL COMMENT '起点实体',
  to_entity_id bigint(20) NOT NULL COMMENT '终点实体',
  relation_type varchar(60) NOT NULL COMMENT '关系类型',
  source_id bigint(20) NOT NULL COMMENT '知识来源ID',
  version_id bigint(20) NOT NULL COMMENT '知识版本ID',
  chunk_id bigint(20) NOT NULL COMMENT '原文切片ID',
  period varchar(20) DEFAULT '' COMMENT '时间维度，如2023 Q3',
  data_type varchar(20) NOT NULL COMMENT 'PDF/NEWS/POLICY/REPORT',
  evidence_snippet varchar(1000) DEFAULT '' COMMENT '关系原文证据',
  evidence_start int DEFAULT 0 COMMENT '证据在切片中的起始偏移',
  evidence_end int DEFAULT 0 COMMENT '证据在切片中的结束偏移',
  create_time datetime DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_business_kb_relation (from_entity_id,to_entity_id,relation_type,chunk_id),
  KEY idx_business_kb_relation_filter (period,data_type),
  KEY idx_business_kb_relation_chunk (chunk_id),
  KEY idx_business_kb_relation_version (version_id)
) ENGINE=InnoDB COMMENT='知识图谱可溯源关系';

SET @kb_has_start = (SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=database() AND table_name='business_kb_relation' AND column_name='evidence_start');
SET @kb_add_start = IF(@kb_has_start=0, 'ALTER TABLE business_kb_relation ADD COLUMN evidence_start int DEFAULT 0 AFTER evidence_snippet, ADD COLUMN evidence_end int DEFAULT 0 AFTER evidence_start', 'SELECT 1');
PREPARE kb_stmt FROM @kb_add_start; EXECUTE kb_stmt; DEALLOCATE PREPARE kb_stmt;
