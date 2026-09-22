CREATE TABLE IF NOT EXISTS business_kb_fact (
  id            bigint(20)   NOT NULL AUTO_INCREMENT COMMENT '事实ID',
  source_id     bigint(20)   NOT NULL COMMENT '资料ID',
  version_id    bigint(20)   NOT NULL COMMENT '版本ID',
  chunk_id      bigint(20)   DEFAULT NULL COMMENT '来源切片',
  page_start    int          DEFAULT NULL COMMENT '页码',
  row_label     varchar(200) NOT NULL COMMENT '行指标',
  col_header    varchar(200) DEFAULT '' COMMENT '列头',
  metric_label  varchar(200) NOT NULL COMMENT '检索用指标名',
  raw_value     varchar(80)  NOT NULL COMMENT '原文数值',
  unit          varchar(40)  DEFAULT '' COMMENT '单位',
  fact_year     int          DEFAULT NULL COMMENT '年份',
  create_time   datetime     DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_business_kb_fact_version (version_id, metric_label),
  KEY idx_business_kb_fact_source (source_id, version_id)
) ENGINE=InnoDB COMMENT='知识库结构化指标事实';
