-- ----------------------------
-- 第一份竞争社洞察报告增量字段
-- 已有环境执行一次；新库直接执行 business_table.sql。
-- ----------------------------

ALTER TABLE business_report
  ADD COLUMN import_task_id bigint(20) DEFAULT NULL COMMENT '来源Excel解析任务ID' AFTER id,
  ADD COLUMN report_type varchar(64) DEFAULT '' COMMENT '报告类型' AFTER status,
  ADD COLUMN generation_mode varchar(64) DEFAULT '' COMMENT '生成模式' AFTER report_type,
  ADD COLUMN report_content longtext COMMENT '结构化报告JSON' AFTER generation_mode,
  ADD KEY idx_business_report_import_task (import_task_id);
