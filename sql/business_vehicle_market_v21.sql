-- 整车市场分析v21增量迁移：只补缺失字段和索引，不删除或覆盖现有数据。
DELIMITER $$
DROP PROCEDURE IF EXISTS upgrade_business_analysis_vehicle_v21$$
CREATE PROCEDURE upgrade_business_analysis_vehicle_v21()
BEGIN
  CREATE TABLE IF NOT EXISTS business_analysis_vehicle (
    id bigint NOT NULL AUTO_INCREMENT, task_name varchar(200) DEFAULT '',
    status char(1) DEFAULT '0', create_by varchar(64) DEFAULT '', create_time datetime DEFAULT NULL,
    update_by varchar(64) DEFAULT '', update_time datetime DEFAULT NULL, remark varchar(500) DEFAULT '',
    PRIMARY KEY (id)
  ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='整车市场分析任务';

  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='business_analysis_vehicle' AND column_name='dataset_id') THEN
    ALTER TABLE business_analysis_vehicle ADD COLUMN dataset_id varchar(80) DEFAULT NULL COMMENT 'Python分析数据集ID' AFTER task_name;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='business_analysis_vehicle' AND column_name='file_name') THEN
    ALTER TABLE business_analysis_vehicle ADD COLUMN file_name varchar(255) DEFAULT '' COMMENT '源文件显示名称' AFTER dataset_id;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='business_analysis_vehicle' AND column_name='source_files_json') THEN
    ALTER TABLE business_analysis_vehicle ADD COLUMN source_files_json text COMMENT '源文件列表JSON' AFTER file_name;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='business_analysis_vehicle' AND column_name='row_count') THEN
    ALTER TABLE business_analysis_vehicle ADD COLUMN row_count bigint DEFAULT 0 COMMENT '标准化记录数' AFTER source_files_json;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='business_analysis_vehicle' AND column_name='sheet_count') THEN
    ALTER TABLE business_analysis_vehicle ADD COLUMN sheet_count int DEFAULT 0 COMMENT '工作表数' AFTER row_count;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='business_analysis_vehicle' AND column_name='issue_count') THEN
    ALTER TABLE business_analysis_vehicle ADD COLUMN issue_count int DEFAULT 0 COMMENT '数据质量问题数' AFTER sheet_count;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='business_analysis_vehicle' AND column_name='parser_version') THEN
    ALTER TABLE business_analysis_vehicle ADD COLUMN parser_version varchar(32) DEFAULT '21.0' COMMENT '解析引擎版本' AFTER issue_count;
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='business_analysis_vehicle' AND index_name='uk_market_dataset_id') THEN
    ALTER TABLE business_analysis_vehicle ADD UNIQUE KEY uk_market_dataset_id (dataset_id);
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='business_analysis_vehicle' AND index_name='idx_market_create_by_time') THEN
    ALTER TABLE business_analysis_vehicle ADD KEY idx_market_create_by_time (create_by, create_time);
  END IF;
END$$
CALL upgrade_business_analysis_vehicle_v21()$$
DROP PROCEDURE upgrade_business_analysis_vehicle_v21$$
DELIMITER ;
