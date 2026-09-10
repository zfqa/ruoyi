-- 自由文本LLM实体抽取增量字段（执行一次）。
ALTER TABLE business_data_text
  ADD COLUMN source_text longtext COMMENT '用户粘贴的原始文本或表格片段' AFTER status,
  ADD COLUMN result_json longtext COMMENT 'LLM抽取及Java标准化结果JSON' AFTER source_text,
  ADD COLUMN entity_count int(11) DEFAULT 0 COMMENT '抽取实体数量' AFTER result_json,
  ADD COLUMN llm_model varchar(120) DEFAULT '' COMMENT '实际使用的LLM模型' AFTER entity_count,
  ADD COLUMN completed_time datetime COMMENT '完成时间' AFTER llm_model;
