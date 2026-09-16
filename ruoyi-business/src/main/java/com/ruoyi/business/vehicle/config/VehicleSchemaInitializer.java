package com.ruoyi.business.vehicle.config;

import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** POC启动时幂等补齐懂车帝车辆采集相关表。 */
@Component
public class VehicleSchemaInitializer implements InitializingBean
{
    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public VehicleSchemaInitializer(DataSource dataSource,
        @Value("${business.vehicle.schema-auto-init:true}") boolean enabled)
    {
        this.jdbc = new JdbcTemplate(dataSource);
        this.enabled = enabled;
    }

    @Override
    public void afterPropertiesSet()
    {
        if (!enabled) return;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS business_vehicle_collect_task (
              id bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
              source_code varchar(32) NOT NULL DEFAULT 'dongchedi' COMMENT '数据来源',
              brand_name varchar(100) NOT NULL COMMENT '品牌名称',
              series_id varchar(64) NOT NULL COMMENT '懂车帝车系ID',
              series_name varchar(200) NOT NULL COMMENT '车系名称',
              status char(1) NOT NULL DEFAULT '1' COMMENT '状态（1处理中 2成功 3失败）',
              started_time datetime DEFAULT NULL COMMENT '开始时间',
              completed_time datetime DEFAULT NULL COMMENT '完成时间',
              fetched_count int NOT NULL DEFAULT 0 COMMENT 'Python返回车型数',
              inserted_count int NOT NULL DEFAULT 0 COMMENT 'MySQL新增数',
              updated_count int NOT NULL DEFAULT 0 COMMENT 'MySQL更新数',
              existing_count int NOT NULL DEFAULT 0 COMMENT 'MySQL已有数',
              failed_count int NOT NULL DEFAULT 0 COMMENT '同步失败数',
              error_message varchar(1000) DEFAULT '' COMMENT '安全错误摘要',
              create_by varchar(64) DEFAULT '' COMMENT '创建者',
              create_time datetime DEFAULT NULL COMMENT '创建时间',
              update_by varchar(64) DEFAULT '' COMMENT '更新者',
              update_time datetime DEFAULT NULL COMMENT '更新时间',
              remark varchar(500) DEFAULT '' COMMENT '备注',
              PRIMARY KEY (id),
              KEY idx_business_vehicle_collect_status (status),
              KEY idx_business_vehicle_collect_series (source_code, series_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='懂车帝车辆采集任务'
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS business_vehicle_model (
              id bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
              source_code varchar(32) NOT NULL DEFAULT 'dongchedi' COMMENT '数据来源',
              dongchedi_car_id varchar(64) NOT NULL COMMENT '懂车帝车型ID',
              dongchedi_series_id varchar(64) NOT NULL COMMENT '懂车帝车系ID',
              brand_name varchar(100) DEFAULT '' COMMENT '品牌名称',
              series_name varchar(200) DEFAULT '' COMMENT '车系名称',
              model_name varchar(500) DEFAULT '' COMMENT '车型名称',
              manufacturer varchar(200) DEFAULT '' COMMENT '厂商',
              official_guide_price varchar(100) DEFAULT '' COMMENT '官方指导价原文',
              level varchar(100) DEFAULT '' COMMENT '车辆级别',
              energy_type varchar(100) DEFAULT '' COMMENT '能源类型',
              instrument_screen_size_inch varchar(100) DEFAULT '' COMMENT '仪表屏尺寸',
              instrument_screen_style varchar(100) DEFAULT '' COMMENT '仪表屏样式',
              center_screen_size_inch varchar(100) DEFAULT '' COMMENT '中控屏尺寸',
              center_screen_material varchar(100) DEFAULT '' COMMENT '中控屏材质',
              passenger_screen_size_inch varchar(100) DEFAULT '' COMMENT '副驾屏尺寸',
              rear_screen_size_inch varchar(100) DEFAULT '' COMMENT '后排屏尺寸',
              source_url varchar(1000) DEFAULT '' COMMENT '懂车帝参数页',
              field_sources_json longtext COMMENT '字段来源JSON',
              field_status_json longtext COMMENT '字段状态JSON',
              raw_json longtext COMMENT 'Python原始车型结构JSON',
              first_seen_task_id bigint(20) DEFAULT NULL COMMENT '首次入库任务',
              last_seen_task_id bigint(20) DEFAULT NULL COMMENT '最近采集任务',
              first_seen_time datetime DEFAULT NULL COMMENT '首次入库时间',
              last_crawled_time datetime DEFAULT NULL COMMENT '最近采集时间',
              create_by varchar(64) DEFAULT '' COMMENT '创建者',
              create_time datetime DEFAULT NULL COMMENT '创建时间',
              update_by varchar(64) DEFAULT '' COMMENT '更新者',
              update_time datetime DEFAULT NULL COMMENT '更新时间',
              remark varchar(500) DEFAULT '' COMMENT '备注',
              PRIMARY KEY (id),
              UNIQUE KEY uk_business_vehicle_model_source_car (source_code, dongchedi_car_id),
              KEY idx_business_vehicle_model_series (source_code, dongchedi_series_id),
              KEY idx_business_vehicle_model_brand_series (brand_name, series_name)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='懂车帝车型主数据'
            """);
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS business_vehicle_collect_model (
              id bigint(20) NOT NULL AUTO_INCREMENT COMMENT '主键',
              collect_task_id bigint(20) NOT NULL COMMENT 'business_vehicle_collect_task.id',
              model_id bigint(20) NOT NULL COMMENT 'business_vehicle_model.id',
              operation varchar(32) NOT NULL DEFAULT '' COMMENT 'INSERTED/UPDATED/EXISTING',
              create_time datetime DEFAULT NULL COMMENT '创建时间',
              PRIMARY KEY (id),
              UNIQUE KEY uk_business_vehicle_collect_model (collect_task_id, model_id),
              KEY idx_business_vehicle_collect_model_task (collect_task_id),
              KEY idx_business_vehicle_collect_model_model (model_id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='懂车帝采集任务车型关系'
            """);
    }
}
