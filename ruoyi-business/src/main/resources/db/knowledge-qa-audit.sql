CREATE TABLE IF NOT EXISTS business_kb_qa_session (
  task_id varchar(32) NOT NULL, task_owner varchar(64) NOT NULL DEFAULT '', question text NOT NULL,
  source_type varchar(20) DEFAULT '', include_news tinyint(1) NOT NULL DEFAULT 1,
  status varchar(20) NOT NULL, progress int NOT NULL DEFAULT 0, current_stage varchar(200) DEFAULT '',
  error_message varchar(1000) DEFAULT '', snapshot_json longtext NOT NULL,
  started_time datetime DEFAULT NULL, finished_time datetime DEFAULT NULL,
  create_time datetime DEFAULT NULL, update_time datetime DEFAULT NULL,
  PRIMARY KEY (task_id), KEY idx_business_kb_qa_owner_time (task_owner,create_time),
  KEY idx_business_kb_qa_status_time (status,create_time)
) ENGINE=InnoDB COMMENT='知识问答任务与完整审计快照';

CREATE TABLE IF NOT EXISTS business_kb_qa_claim (
  id bigint(20) NOT NULL AUTO_INCREMENT, task_id varchar(32) NOT NULL, claim_no int NOT NULL,
  claim_text text NOT NULL, verified tinyint(1) NOT NULL DEFAULT 1, create_time datetime DEFAULT NULL,
  PRIMARY KEY (id), UNIQUE KEY uk_business_kb_qa_claim (task_id,claim_no)
) ENGINE=InnoDB COMMENT='知识问答逐句事实结论';

CREATE TABLE IF NOT EXISTS business_kb_qa_citation (
  id bigint(20) NOT NULL AUTO_INCREMENT, task_id varchar(32) NOT NULL, claim_no int NOT NULL,
  citation_label varchar(20) NOT NULL, chunk_id bigint(20) NOT NULL, source_name varchar(255) DEFAULT '',
  page_start int DEFAULT NULL, page_end int DEFAULT NULL, start_offset int DEFAULT NULL, end_offset int DEFAULT NULL,
  evidence_snippet text, create_time datetime DEFAULT NULL,
  PRIMARY KEY (id), KEY idx_business_kb_qa_citation_task (task_id,claim_no),
  KEY idx_business_kb_qa_citation_chunk (chunk_id)
) ENGINE=InnoDB COMMENT='知识问答逐句引用证据';
