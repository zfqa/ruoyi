-- ----------------------------
-- 固定知识库 POC：资料、版本、入库任务、文档切片及来源追溯
-- 已有环境执行一次；不会删除原 business_knowledge 数据。
-- ----------------------------

ALTER TABLE business_knowledge
  CHANGE COLUMN task_name source_name varchar(255) NOT NULL COMMENT '资料名称',
  ADD COLUMN source_code varchar(100) DEFAULT NULL COMMENT '固定资料编码' AFTER id,
  ADD COLUMN source_type varchar(20) NOT NULL DEFAULT 'PDF' COMMENT '来源类型：PDF/NEWS/REPORT' AFTER source_name,
  ADD COLUMN current_version_id bigint(20) DEFAULT NULL COMMENT '当前有效版本ID' AFTER source_type,
  ADD COLUMN owner_dept_id bigint(20) DEFAULT NULL COMMENT '归属部门' AFTER current_version_id,
  ADD COLUMN confidentiality varchar(20) NOT NULL DEFAULT 'INTERNAL' COMMENT '密级' AFTER owner_dept_id,
  ADD COLUMN allowed_purpose varchar(500) DEFAULT '' COMMENT '允许使用范围' AFTER confidentiality,
  ADD COLUMN allowed_role_ids varchar(500) DEFAULT '' COMMENT '允许访问角色ID，逗号分隔；空为继承菜单权限' AFTER allowed_purpose,
  ADD COLUMN enabled char(1) NOT NULL DEFAULT '1' COMMENT '是否启用（0否 1是）' AFTER allowed_role_ids,
  ADD UNIQUE KEY uk_business_kb_source_code (source_code),
  ADD KEY idx_business_kb_type_status (source_type, status, enabled);

CREATE TABLE IF NOT EXISTS business_kb_version (
  id                bigint(20)      NOT NULL AUTO_INCREMENT COMMENT '版本ID',
  source_id         bigint(20)      NOT NULL COMMENT '资料ID',
  version_no        varchar(64)     NOT NULL COMMENT '版本号',
  original_name     varchar(255)    DEFAULT '' COMMENT '原始文件名/新闻标题',
  stored_path       varchar(500)    DEFAULT '' COMMENT '受控文件路径',
  source_url        varchar(1000)   DEFAULT '' COMMENT '新闻原始URL',
  content_sha256    char(64)        NOT NULL COMMENT '内容SHA-256',
  published_time    datetime        DEFAULT NULL COMMENT '发布时间',
  fetched_time      datetime        DEFAULT NULL COMMENT '抓取时间',
  parser_version    varchar(64)     DEFAULT '' COMMENT '解析器版本',
  page_count        int             DEFAULT 0 COMMENT 'PDF页数',
  chunk_count       int             DEFAULT 0 COMMENT '切片数',
  status            char(1)         NOT NULL DEFAULT '0' COMMENT '0待处理 1处理中 2成功 3失败',
  error_message     varchar(1000)   DEFAULT '' COMMENT '失败原因',
  create_by         varchar(64)     DEFAULT '',
  create_time       datetime        DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_business_kb_version_hash (source_id, content_sha256),
  UNIQUE KEY uk_business_kb_version_no (source_id, version_no),
  KEY idx_business_kb_version_status (status, create_time)
) ENGINE=InnoDB COMMENT='知识库资料版本';

CREATE TABLE IF NOT EXISTS business_kb_ingest_task (
  id                bigint(20)      NOT NULL AUTO_INCREMENT COMMENT '任务ID',
  source_id         bigint(20)      NOT NULL COMMENT '资料ID',
  version_id        bigint(20)      NOT NULL COMMENT '版本ID',
  status            char(1)         NOT NULL DEFAULT '0' COMMENT '0排队 1处理中 2成功 3失败',
  progress          int             NOT NULL DEFAULT 0 COMMENT '进度0-100',
  current_stage     varchar(100)    DEFAULT '' COMMENT '当前阶段',
  chunk_count       int             DEFAULT 0 COMMENT '已生成切片数',
  error_message     varchar(1000)   DEFAULT '' COMMENT '失败原因',
  started_time      datetime        DEFAULT NULL,
  finished_time     datetime        DEFAULT NULL,
  create_by         varchar(64)     DEFAULT '',
  create_time       datetime        DEFAULT NULL,
  update_time       datetime        DEFAULT NULL,
  PRIMARY KEY (id),
  KEY idx_business_kb_task_source (source_id, create_time),
  KEY idx_business_kb_task_status (status, create_time)
) ENGINE=InnoDB COMMENT='知识库异步入库任务';

CREATE TABLE IF NOT EXISTS business_kb_chunk (
  id                bigint(20)      NOT NULL AUTO_INCREMENT COMMENT '切片ID',
  source_id         bigint(20)      NOT NULL COMMENT '资料ID',
  version_id        bigint(20)      NOT NULL COMMENT '版本ID',
  chunk_no          int             NOT NULL COMMENT '版本内切片序号',
  title_path        varchar(500)    DEFAULT '' COMMENT '标题层级',
  content           longtext        NOT NULL COMMENT '切片正文',
  source_snippet    text            COMMENT '引用时展示的原文片段',
  page_start        int             DEFAULT NULL COMMENT 'PDF起始页',
  page_end          int             DEFAULT NULL COMMENT 'PDF结束页',
  source_url        varchar(1000)   DEFAULT '' COMMENT '新闻URL',
  report_id         bigint(20)      DEFAULT NULL COMMENT '结构化报告ID',
  metric_id         varchar(200)    DEFAULT '' COMMENT '报告指标ID',
  evidence_json     longtext        COMMENT '结构化来源证据',
  content_sha256    char(64)        NOT NULL COMMENT '切片内容哈希',
  token_count       int             DEFAULT 0 COMMENT '估算Token数',
  create_time       datetime        DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_business_kb_chunk_no (version_id, chunk_no),
  KEY idx_business_kb_chunk_source (source_id, version_id),
  KEY idx_business_kb_chunk_metric (report_id, metric_id),
  FULLTEXT KEY ft_business_kb_chunk_content (title_path, content) WITH PARSER ngram
) ENGINE=InnoDB COMMENT='知识库文档切片与来源';

