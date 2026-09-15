package com.ruoyi.business.analysis.vehicle.config;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** POC启动时通过RuoYi现有数据源幂等补齐整车市场分析任务表。 */
@Component
public class VehicleAnalysisSchemaInitializer implements InitializingBean
{
    private static final String TABLE = "business_analysis_vehicle";
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public VehicleAnalysisSchemaInitializer(DataSource dataSource,
        @Value("${business.vehicle.schema-auto-init:true}") boolean enabled)
    {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        this.enabled = enabled;
    }

    @Override
    public void afterPropertiesSet() throws SQLException
    {
        if (!enabled) return;
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS business_analysis_vehicle (
              id bigint NOT NULL AUTO_INCREMENT,
              task_name varchar(200) DEFAULT '', dataset_id varchar(80) DEFAULT NULL,
              file_name varchar(255) DEFAULT '', source_files_json text,
              row_count bigint DEFAULT 0, sheet_count int DEFAULT 0, issue_count int DEFAULT 0,
              parser_version varchar(32) DEFAULT '21.0', status char(1) DEFAULT '0',
              create_by varchar(64) DEFAULT '', create_time datetime DEFAULT NULL,
              update_by varchar(64) DEFAULT '', update_time datetime DEFAULT NULL,
              remark varchar(500) DEFAULT '', PRIMARY KEY (id)
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='整车市场分析任务'
            """);

        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("dataset_id", "varchar(80) DEFAULT NULL COMMENT 'Python分析数据集ID'");
        columns.put("file_name", "varchar(255) DEFAULT '' COMMENT '源文件显示名称'");
        columns.put("source_files_json", "text COMMENT '源文件列表JSON'");
        columns.put("row_count", "bigint DEFAULT 0 COMMENT '标准化记录数'");
        columns.put("sheet_count", "int DEFAULT 0 COMMENT '工作表数'");
        columns.put("issue_count", "int DEFAULT 0 COMMENT '数据质量问题数'");
        columns.put("parser_version", "varchar(32) DEFAULT '21.0' COMMENT '解析引擎版本'");

        try (Connection connection = dataSource.getConnection())
        {
            for (Map.Entry<String, String> entry : columns.entrySet())
                if (!columnExists(connection, entry.getKey()))
                    jdbc.execute("ALTER TABLE " + TABLE + " ADD COLUMN " + entry.getKey() + " " + entry.getValue());
            if (!indexExists(connection, "uk_market_dataset_id"))
                jdbc.execute("ALTER TABLE " + TABLE + " ADD UNIQUE KEY uk_market_dataset_id (dataset_id)");
            if (!indexExists(connection, "idx_market_create_by_time"))
                jdbc.execute("ALTER TABLE " + TABLE + " ADD KEY idx_market_create_by_time (create_by, create_time)");
        }
    }

    private boolean columnExists(Connection connection, String column) throws SQLException
    {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet result = metadata.getColumns(connection.getCatalog(), null, TABLE, column))
        {
            if (result.next()) return true;
        }
        try (ResultSet result = metadata.getColumns(connection.getCatalog(), null,
            TABLE.toUpperCase(Locale.ROOT), column.toUpperCase(Locale.ROOT)))
        {
            return result.next();
        }
    }

    private boolean indexExists(Connection connection, String indexName) throws SQLException
    {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet result = metadata.getIndexInfo(connection.getCatalog(), null, TABLE, false, false))
        {
            while (result.next())
                if (indexName.equalsIgnoreCase(result.getString("INDEX_NAME"))) return true;
        }
        return false;
    }
}
