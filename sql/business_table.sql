-- ----------------------------
-- 业务模块表结构初始化
-- ----------------------------

-- Excel/CSV导入与字段映射
DROP TABLE IF EXISTS business_data_excel;
CREATE TABLE business_data_excel (
  id                bigint(20)      NOT NULL AUTO_INCREMENT    COMMENT '主键',
  task_name         varchar(200)    DEFAULT ''                 COMMENT '任务名称',
  file_name         varchar(255)    DEFAULT ''                 COMMENT '原始文件名',
  file_path         varchar(500)    DEFAULT ''                 COMMENT '上传文件路径',
  workbook_id       varchar(100)    DEFAULT ''                 COMMENT '工作簿哈希ID',
  sheet_count       int             DEFAULT 0                  COMMENT 'Sheet数量',
  table_count       int             DEFAULT 0                  COMMENT '识别表格数量',
  record_count      int             DEFAULT 0                  COMMENT '预览记录数量',
  result_json       longtext                                   COMMENT '解析结果JSON',
  status            char(1)         DEFAULT '0'                COMMENT '状态（0待处理 1处理中 2成功 3失败）',
  create_by         varchar(64)     DEFAULT ''                 COMMENT '创建者',
  create_time       datetime                                   COMMENT '创建时间',
  update_by         varchar(64)     DEFAULT ''                 COMMENT '更新者',
  update_time       datetime                                   COMMENT '更新时间',
  remark            varchar(500)    DEFAULT ''                 COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='Excel/CSV导入与字段映射';

-- 文本型PDF正文与规则表格解析
DROP TABLE IF EXISTS business_data_pdf;
CREATE TABLE business_data_pdf (
  id                bigint(20)      NOT NULL AUTO_INCREMENT    COMMENT '主键',
  task_name         varchar(200)    DEFAULT ''                 COMMENT '任务名称',
  status            char(1)         DEFAULT '0'                COMMENT '状态（0待处理 1处理中 2成功 3失败）',
  original_file_name varchar(255)   DEFAULT ''                 COMMENT '用户上传的原始文件名',
  stored_file_path  varchar(500)    DEFAULT ''                 COMMENT '私有原始文件逻辑引用',
  source_text       longtext                                   COMMENT '用户粘贴的原始文本或表格片段',
  result_json       longtext                                   COMMENT 'LLM抽取及Java标准化结果JSON',
  entity_count      int(11)         DEFAULT 0                  COMMENT '抽取实体数量',
  llm_model         varchar(120)    DEFAULT ''                 COMMENT '实际使用的LLM模型',
  completed_time    datetime                                   COMMENT '完成时间',
  error_message     varchar(1000)   DEFAULT ''                 COMMENT '安全解析失败摘要',
  create_by         varchar(64)     DEFAULT ''                 COMMENT '创建者',
  create_time       datetime                                   COMMENT '创建时间',
  update_by         varchar(64)     DEFAULT ''                 COMMENT '更新者',
  update_time       datetime                                   COMMENT '更新时间',
  remark            varchar(500)    DEFAULT ''                 COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='文本型PDF正文与规则表格解析';

-- 自由文本结构化
DROP TABLE IF EXISTS business_data_text;
CREATE TABLE business_data_text (
  id                bigint(20)      NOT NULL AUTO_INCREMENT    COMMENT '主键',
  task_name         varchar(200)    DEFAULT ''                 COMMENT '任务名称',
  status            char(1)         DEFAULT '0'                COMMENT '状态（0待处理 1处理中 2成功 3失败）',
  create_by         varchar(64)     DEFAULT ''                 COMMENT '创建者',
  create_time       datetime                                   COMMENT '创建时间',
  update_by         varchar(64)     DEFAULT ''                 COMMENT '更新者',
  update_time       datetime                                   COMMENT '更新时间',
  remark            varchar(500)    DEFAULT ''                 COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='自由文本结构化';

-- 整车市场分析-基础统计、排名与趋势
DROP TABLE IF EXISTS business_analysis_vehicle;
CREATE TABLE business_analysis_vehicle (
  id                bigint(20)      NOT NULL AUTO_INCREMENT    COMMENT '主键',
  task_name         varchar(200)    DEFAULT ''                 COMMENT '任务名称',
  status            char(1)         DEFAULT '0'                COMMENT '状态（0待处理 1处理中 2成功 3失败）',
  create_by         varchar(64)     DEFAULT ''                 COMMENT '创建者',
  create_time       datetime                                   COMMENT '创建时间',
  update_by         varchar(64)     DEFAULT ''                 COMMENT '更新者',
  update_time       datetime                                   COMMENT '更新时间',
  remark            varchar(500)    DEFAULT ''                 COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='整车市场分析';

-- 车载显示分析-标准模板基础统计
DROP TABLE IF EXISTS business_analysis_display;
CREATE TABLE business_analysis_display (
  id                bigint(20)      NOT NULL AUTO_INCREMENT    COMMENT '主键',
  task_name         varchar(200)    DEFAULT ''                 COMMENT '任务名称',
  status            char(1)         DEFAULT '0'                COMMENT '状态（0待处理 1处理中 2成功 3失败）',
  create_by         varchar(64)     DEFAULT ''                 COMMENT '创建者',
  create_time       datetime                                   COMMENT '创建时间',
  update_by         varchar(64)     DEFAULT ''                 COMMENT '更新者',
  update_time       datetime                                   COMMENT '更新时间',
  remark            varchar(500)    DEFAULT ''                 COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='车载显示分析';

-- 白名单官网新闻抓取
DROP TABLE IF EXISTS business_news_collect;
CREATE TABLE business_news_collect (
  id                bigint(20)      NOT NULL AUTO_INCREMENT    COMMENT '主键',
  task_name         varchar(200)    DEFAULT ''                 COMMENT '任务名称',
  source_name       varchar(100)    DEFAULT ''                 COMMENT 'Python新闻源名称',
  trigger_type      varchar(16)     DEFAULT 'MANUAL'           COMMENT '触发类型',
  status            char(1)         DEFAULT '0'                COMMENT '状态（0待处理 1处理中 2成功 3失败）',
  publish_time_start varchar(32)    DEFAULT NULL,
  publish_time_end  varchar(32)     DEFAULT NULL,
  force_flag        char(1)         DEFAULT '0',
  started_time      datetime        DEFAULT NULL,
  completed_time    datetime        DEFAULT NULL,
  fetched_count     int NOT NULL DEFAULT 0,
  inserted_count    int NOT NULL DEFAULT 0,
  updated_count     int NOT NULL DEFAULT 0,
  duplicate_count   int NOT NULL DEFAULT 0,
  filtered_count    int NOT NULL DEFAULT 0,
  failed_count      int NOT NULL DEFAULT 0,
  mysql_inserted_count int NOT NULL DEFAULT 0,
  mysql_updated_count int NOT NULL DEFAULT 0,
  mysql_existing_count int NOT NULL DEFAULT 0,
  statistics_version varchar(16) DEFAULT NULL,
  crawl_run_id      varchar(64) DEFAULT NULL,
  error_message     varchar(500) DEFAULT '',
  create_by         varchar(64)     DEFAULT ''                 COMMENT '创建者',
  create_time       datetime                                   COMMENT '创建时间',
  update_by         varchar(64)     DEFAULT ''                 COMMENT '更新者',
  update_time       datetime                                   COMMENT '更新时间',
  remark            varchar(500)    DEFAULT ''                 COMMENT '备注',
  PRIMARY KEY (id),
  KEY idx_business_news_collect_source_status (source_name, status)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='白名单官网新闻抓取';

DROP TABLE IF EXISTS business_news_article;
CREATE TABLE business_news_article (
  id bigint(20) NOT NULL AUTO_INCREMENT, source_name varchar(100) NOT NULL,
  source_site varchar(255) NOT NULL, title varchar(500) NOT NULL, content longtext NOT NULL,
  url varchar(2000) NOT NULL, original_url varchar(2000) NOT NULL, canonical_url varchar(2000) NOT NULL,
  published_at varchar(64) DEFAULT NULL, crawled_at varchar(64) NOT NULL,
  matched_keywords text, content_hash char(64) NOT NULL, crawl_task_id bigint(20) DEFAULT NULL,
  create_by varchar(64) DEFAULT '', create_time datetime DEFAULT NULL,
  update_by varchar(64) DEFAULT '', update_time datetime DEFAULT NULL, remark varchar(500) DEFAULT '',
  PRIMARY KEY (id), UNIQUE KEY uk_business_news_article_canonical (canonical_url(255)),
  UNIQUE KEY uk_business_news_article_hash (content_hash)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='官网新闻采集业务明细';

DROP TABLE IF EXISTS business_news_collect_article;
CREATE TABLE business_news_collect_article (
  id bigint(20) NOT NULL AUTO_INCREMENT, crawl_task_id bigint(20) NOT NULL,
  article_id bigint(20) NOT NULL, operation varchar(32) NOT NULL,
  mysql_operation varchar(32) DEFAULT NULL, create_time datetime DEFAULT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_news_collect_article_task_article (crawl_task_id, article_id),
  KEY idx_news_collect_article_task (crawl_task_id), KEY idx_news_collect_article_article (article_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='新闻采集任务与文章关系';

-- 新闻清洗、分类与事件提取
DROP TABLE IF EXISTS business_news_process;
CREATE TABLE business_news_process (
  id                bigint(20)      NOT NULL AUTO_INCREMENT    COMMENT '主键',
  task_name         varchar(200)    DEFAULT ''                 COMMENT '任务名称',
  status            char(1)         DEFAULT '0'                COMMENT '状态（0待处理 1处理中 2成功 3失败）',
  create_by         varchar(64)     DEFAULT ''                 COMMENT '创建者',
  create_time       datetime                                   COMMENT '创建时间',
  update_by         varchar(64)     DEFAULT ''                 COMMENT '更新者',
  update_time       datetime                                   COMMENT '更新时间',
  remark            varchar(500)    DEFAULT ''                 COMMENT '备注',
  PRIMARY KEY (id)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='新闻清洗分类与事件提取';

-- 固定文件知识库及来源展示
DROP TABLE IF EXISTS business_knowledge;
CREATE TABLE business_knowledge (
  id                bigint(20)      NOT NULL AUTO_INCREMENT    COMMENT '主键',
  source_code       varchar(100)    DEFAULT NULL               COMMENT '固定资料编码',
  source_name       varchar(255)    NOT NULL                   COMMENT '资料名称',
  source_type       varchar(20)     NOT NULL DEFAULT 'PDF'     COMMENT '来源类型：PDF/NEWS/POLICY/REPORT',
  current_version_id bigint(20)     DEFAULT NULL               COMMENT '当前有效版本ID',
  owner_dept_id     bigint(20)      DEFAULT NULL               COMMENT '归属部门',
  confidentiality  varchar(20)     NOT NULL DEFAULT 'INTERNAL' COMMENT '密级',
  allowed_purpose   varchar(500)    DEFAULT ''                 COMMENT '允许使用范围',
  allowed_role_ids  varchar(500)    DEFAULT ''                 COMMENT '允许访问角色ID',
  enabled           char(1)         NOT NULL DEFAULT '1'       COMMENT '是否启用',
  status            char(1)         DEFAULT '0'                COMMENT '状态（0待处理 1处理中 2成功 3失败）',
  create_by         varchar(64)     DEFAULT ''                 COMMENT '创建者',
  create_time       datetime                                   COMMENT '创建时间',
  update_by         varchar(64)     DEFAULT ''                 COMMENT '更新者',
  update_time       datetime                                   COMMENT '更新时间',
  remark            varchar(500)    DEFAULT ''                 COMMENT '备注',
  PRIMARY KEY (id),
  UNIQUE KEY uk_business_kb_source_code (source_code),
  KEY idx_business_kb_type_status (source_type, status, enabled)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='固定文件知识库及来源展示';

CREATE TABLE business_kb_version (
  id bigint(20) NOT NULL AUTO_INCREMENT, source_id bigint(20) NOT NULL, version_no varchar(64) NOT NULL,
  original_name varchar(255) DEFAULT '', stored_path varchar(500) DEFAULT '', source_url varchar(1000) DEFAULT '',
  content_sha256 char(64) NOT NULL, published_time datetime DEFAULT NULL, fetched_time datetime DEFAULT NULL,
  parser_version varchar(64) DEFAULT '', page_count int DEFAULT 0, chunk_count int DEFAULT 0,
  status char(1) NOT NULL DEFAULT '0', error_message varchar(1000) DEFAULT '', create_by varchar(64) DEFAULT '',
  create_time datetime DEFAULT NULL, PRIMARY KEY (id), UNIQUE KEY uk_business_kb_version_hash (source_id, content_sha256),
  UNIQUE KEY uk_business_kb_version_no (source_id, version_no), KEY idx_business_kb_version_status (status, create_time)
) ENGINE=InnoDB COMMENT='知识库资料版本';

CREATE TABLE business_kb_ingest_task (
  id bigint(20) NOT NULL AUTO_INCREMENT, source_id bigint(20) NOT NULL, version_id bigint(20) NOT NULL,
  status char(1) NOT NULL DEFAULT '0', progress int NOT NULL DEFAULT 0, current_stage varchar(100) DEFAULT '',
  chunk_count int DEFAULT 0, error_message varchar(1000) DEFAULT '', started_time datetime DEFAULT NULL,
  finished_time datetime DEFAULT NULL, create_by varchar(64) DEFAULT '', create_time datetime DEFAULT NULL,
  update_time datetime DEFAULT NULL, PRIMARY KEY (id), KEY idx_business_kb_task_source (source_id, create_time),
  KEY idx_business_kb_task_status (status, create_time)
) ENGINE=InnoDB COMMENT='知识库异步入库任务';

CREATE TABLE business_kb_chunk (
  id bigint(20) NOT NULL AUTO_INCREMENT, source_id bigint(20) NOT NULL, version_id bigint(20) NOT NULL,
  chunk_no int NOT NULL, title_path varchar(500) DEFAULT '', content longtext NOT NULL, source_snippet text,
  page_start int DEFAULT NULL, page_end int DEFAULT NULL, source_url varchar(1000) DEFAULT '', report_id bigint(20) DEFAULT NULL,
  metric_id varchar(200) DEFAULT '', evidence_json longtext, content_sha256 char(64) NOT NULL, token_count int DEFAULT 0,
  create_time datetime DEFAULT NULL, PRIMARY KEY (id), UNIQUE KEY uk_business_kb_chunk_no (version_id, chunk_no),
  KEY idx_business_kb_chunk_source (source_id, version_id), KEY idx_business_kb_chunk_metric (report_id, metric_id),
  FULLTEXT KEY ft_business_kb_chunk_content (title_path, content) WITH PARSER ngram
) ENGINE=InnoDB COMMENT='知识库文档切片与来源';

CREATE TABLE business_kb_entity (
  id bigint(20) NOT NULL AUTO_INCREMENT, entity_key varchar(300) NOT NULL, entity_name varchar(255) NOT NULL,
  entity_type varchar(30) NOT NULL, aliases varchar(1000) DEFAULT '', create_time datetime DEFAULT NULL,
  update_time datetime DEFAULT NULL, PRIMARY KEY (id), UNIQUE KEY uk_business_kb_entity_key (entity_key),
  KEY idx_business_kb_entity_type (entity_type, entity_name)
) ENGINE=InnoDB COMMENT='知识图谱实体';

CREATE TABLE business_kb_relation (
  id bigint(20) NOT NULL AUTO_INCREMENT, from_entity_id bigint(20) NOT NULL, to_entity_id bigint(20) NOT NULL,
  relation_type varchar(60) NOT NULL, source_id bigint(20) NOT NULL, version_id bigint(20) NOT NULL,
  chunk_id bigint(20) NOT NULL, period varchar(20) DEFAULT '', data_type varchar(20) NOT NULL,
  evidence_snippet varchar(1000) DEFAULT '', evidence_start int DEFAULT 0, evidence_end int DEFAULT 0,
  create_time datetime DEFAULT NULL, PRIMARY KEY (id),
  UNIQUE KEY uk_business_kb_relation (from_entity_id,to_entity_id,relation_type,chunk_id),
  KEY idx_business_kb_relation_filter (period,data_type), KEY idx_business_kb_relation_chunk (chunk_id),
  KEY idx_business_kb_relation_version (version_id)
) ENGINE=InnoDB COMMENT='知识图谱可溯源关系';

DROP TABLE IF EXISTS business_kb_qa_session;
CREATE TABLE business_kb_qa_session (
  task_id varchar(32) NOT NULL, task_owner varchar(64) NOT NULL DEFAULT '', question text NOT NULL,
  source_type varchar(20) DEFAULT '', include_news tinyint(1) NOT NULL DEFAULT 1,
  status varchar(20) NOT NULL, progress int NOT NULL DEFAULT 0, current_stage varchar(200) DEFAULT '',
  error_message varchar(1000) DEFAULT '', snapshot_json longtext NOT NULL,
  started_time datetime DEFAULT NULL, finished_time datetime DEFAULT NULL,
  create_time datetime DEFAULT NULL, update_time datetime DEFAULT NULL,
  PRIMARY KEY (task_id), KEY idx_business_kb_qa_owner_time (task_owner,create_time),
  KEY idx_business_kb_qa_status_time (status,create_time)
) ENGINE=InnoDB COMMENT='知识问答任务与完整审计快照';

DROP TABLE IF EXISTS business_kb_qa_claim;
CREATE TABLE business_kb_qa_claim (
  id bigint(20) NOT NULL AUTO_INCREMENT, task_id varchar(32) NOT NULL, claim_no int NOT NULL,
  claim_text text NOT NULL, verified tinyint(1) NOT NULL DEFAULT 1, create_time datetime DEFAULT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_business_kb_qa_claim (task_id,claim_no)
) ENGINE=InnoDB COMMENT='知识问答逐句事实结论';

DROP TABLE IF EXISTS business_kb_qa_citation;
CREATE TABLE business_kb_qa_citation (
  id bigint(20) NOT NULL AUTO_INCREMENT, task_id varchar(32) NOT NULL, claim_no int NOT NULL,
  citation_label varchar(20) NOT NULL, chunk_id bigint(20) NOT NULL, source_name varchar(255) DEFAULT '',
  page_start int DEFAULT NULL, page_end int DEFAULT NULL, start_offset int DEFAULT NULL, end_offset int DEFAULT NULL,
  evidence_snippet text, create_time datetime DEFAULT NULL,
  PRIMARY KEY (id), KEY idx_business_kb_qa_citation_task (task_id,claim_no),
  KEY idx_business_kb_qa_citation_chunk (chunk_id)
) ENGINE=InnoDB COMMENT='知识问答逐句引用证据';

-- AI分析与报告
DROP TABLE IF EXISTS business_report;
CREATE TABLE business_report (
  id                bigint(20)      NOT NULL AUTO_INCREMENT    COMMENT '主键',
  import_task_id    bigint(20)      DEFAULT NULL               COMMENT '来源Excel解析任务ID',
  task_name         varchar(200)    DEFAULT ''                 COMMENT '任务名称',
  status            char(1)         DEFAULT '0'                COMMENT '状态（0待处理 1处理中 2成功 3失败）',
  report_type       varchar(64)     DEFAULT ''                 COMMENT '报告类型',
  generation_mode  varchar(64)     DEFAULT ''                 COMMENT '生成模式',
  report_content   longtext                                   COMMENT '结构化报告JSON',
  create_by         varchar(64)     DEFAULT ''                 COMMENT '创建者',
  create_time       datetime                                   COMMENT '创建时间',
  update_by         varchar(64)     DEFAULT ''                 COMMENT '更新者',
  update_time       datetime                                   COMMENT '更新时间',
  remark            varchar(500)    DEFAULT ''                 COMMENT '备注',
  PRIMARY KEY (id),
  KEY idx_business_report_import_task (import_task_id)
) ENGINE=InnoDB AUTO_INCREMENT=1 COMMENT='AI分析与报告';
