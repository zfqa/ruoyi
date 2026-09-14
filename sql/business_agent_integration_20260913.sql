-- 同事新闻采集/PDF解析与现有统一知识库的增量迁移（MySQL 8+）。
-- Python 仅采集/解析；正式知识仍写入 business_knowledge、business_kb_version、business_kb_chunk。

ALTER TABLE business_news_collect
  ADD COLUMN IF NOT EXISTS source_name varchar(100) DEFAULT '' COMMENT 'Python新闻源名称',
  ADD COLUMN IF NOT EXISTS trigger_type varchar(16) DEFAULT 'MANUAL' COMMENT 'MANUAL/SCHEDULED',
  ADD COLUMN IF NOT EXISTS publish_time_start varchar(32) DEFAULT NULL COMMENT '发布时间起始',
  ADD COLUMN IF NOT EXISTS publish_time_end varchar(32) DEFAULT NULL COMMENT '发布时间结束',
  ADD COLUMN IF NOT EXISTS force_flag char(1) DEFAULT '0' COMMENT '是否强制采集',
  ADD COLUMN IF NOT EXISTS started_time datetime DEFAULT NULL,
  ADD COLUMN IF NOT EXISTS completed_time datetime DEFAULT NULL,
  ADD COLUMN IF NOT EXISTS fetched_count int NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS inserted_count int NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS updated_count int NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS duplicate_count int NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS filtered_count int NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS failed_count int NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS mysql_inserted_count int NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS mysql_updated_count int NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS mysql_existing_count int NOT NULL DEFAULT 0,
  ADD COLUMN IF NOT EXISTS statistics_version varchar(16) DEFAULT NULL,
  ADD COLUMN IF NOT EXISTS crawl_run_id varchar(64) DEFAULT NULL,
  ADD COLUMN IF NOT EXISTS error_message varchar(500) DEFAULT '';

CREATE TABLE IF NOT EXISTS business_news_article (
  id bigint(20) NOT NULL AUTO_INCREMENT,
  source_name varchar(100) NOT NULL,
  source_site varchar(255) NOT NULL,
  title varchar(500) NOT NULL,
  content longtext NOT NULL,
  url varchar(2000) NOT NULL,
  original_url varchar(2000) NOT NULL,
  canonical_url varchar(2000) NOT NULL,
  published_at varchar(64) DEFAULT NULL,
  crawled_at varchar(64) NOT NULL,
  matched_keywords text,
  content_hash char(64) NOT NULL,
  crawl_task_id bigint(20) DEFAULT NULL,
  create_by varchar(64) DEFAULT '', create_time datetime DEFAULT NULL,
  update_by varchar(64) DEFAULT '', update_time datetime DEFAULT NULL,
  remark varchar(500) DEFAULT '',
  PRIMARY KEY (id),
  UNIQUE KEY uk_business_news_article_canonical (canonical_url(255)),
  UNIQUE KEY uk_business_news_article_hash (content_hash),
  KEY idx_business_news_article_source_time (source_name, create_time)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='官网新闻采集业务明细';

CREATE TABLE IF NOT EXISTS business_news_collect_article (
  id bigint(20) NOT NULL AUTO_INCREMENT,
  crawl_task_id bigint(20) NOT NULL,
  article_id bigint(20) NOT NULL,
  operation varchar(32) NOT NULL,
  mysql_operation varchar(32) DEFAULT NULL,
  create_time datetime DEFAULT NULL,
  PRIMARY KEY (id),
  UNIQUE KEY uk_news_collect_article_task_article (crawl_task_id, article_id),
  KEY idx_news_collect_article_task (crawl_task_id),
  KEY idx_news_collect_article_article (article_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='新闻采集任务与文章关系';
