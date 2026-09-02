-- ----------------------------
-- Excel智能解析任务表增量字段
-- 适用于已经创建过 business_data_excel 的环境；新库可直接执行 business_table.sql。
-- ----------------------------

ALTER TABLE business_data_excel
  ADD COLUMN file_name varchar(255) DEFAULT '' COMMENT '原始文件名' AFTER task_name,
  ADD COLUMN file_path varchar(500) DEFAULT '' COMMENT '上传文件路径' AFTER file_name,
  ADD COLUMN workbook_id varchar(100) DEFAULT '' COMMENT '工作簿哈希ID' AFTER file_path,
  ADD COLUMN sheet_count int DEFAULT 0 COMMENT 'Sheet数量' AFTER workbook_id,
  ADD COLUMN table_count int DEFAULT 0 COMMENT '识别表格数量' AFTER sheet_count,
  ADD COLUMN record_count int DEFAULT 0 COMMENT '预览记录数量' AFTER table_count,
  ADD COLUMN result_json longtext COMMENT '解析结果JSON' AFTER record_count;
