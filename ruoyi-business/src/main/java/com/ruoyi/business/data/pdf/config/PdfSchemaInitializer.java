package com.ruoyi.business.data.pdf.config;

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

/** POC startup migration for the existing PDF task table, using the configured RuoYi DataSource. */
@Component
public class PdfSchemaInitializer implements InitializingBean
{
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public PdfSchemaInitializer(DataSource dataSource,
        @Value("${business.pdf.schema-auto-init:true}") boolean enabled)
    {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        this.enabled = enabled;
    }

    @Override
    public void afterPropertiesSet() throws SQLException
    {
        if (!enabled) return;
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("original_file_name", "varchar(255) DEFAULT '' COMMENT '用户上传的原始文件名'");
        columns.put("stored_file_path", "varchar(500) DEFAULT '' COMMENT '私有原始文件逻辑引用'");
        columns.put("source_text", "longtext COMMENT '解析出的原始正文'");
        columns.put("result_json", "longtext COMMENT 'PDF结构化解析结果JSON'");
        columns.put("entity_count", "int DEFAULT 0 COMMENT '解析出的实体数量'");
        columns.put("llm_model", "varchar(120) DEFAULT '' COMMENT '实际使用的LLM模型'");
        columns.put("completed_time", "datetime DEFAULT NULL COMMENT '完成时间'");
        columns.put("error_message", "varchar(1000) DEFAULT '' COMMENT '安全解析失败摘要'");
        try (Connection connection = dataSource.getConnection())
        {
            if (!tableExists(connection, "business_data_pdf"))
                throw new SQLException("原数据库缺少business_data_pdf，请先执行项目基础SQL");
            for (Map.Entry<String, String> entry : columns.entrySet())
                if (!columnExists(connection, "business_data_pdf", entry.getKey()))
                    jdbc.execute("ALTER TABLE business_data_pdf ADD COLUMN " + entry.getKey() + " " + entry.getValue());
        }
    }

    private boolean tableExists(Connection connection, String table) throws SQLException
    {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet result = metadata.getTables(connection.getCatalog(), null, table, new String[] {"TABLE"}))
        {
            if (result.next()) return true;
        }
        try (ResultSet result = metadata.getTables(connection.getCatalog(), null, table.toUpperCase(Locale.ROOT), new String[] {"TABLE"}))
        {
            return result.next();
        }
    }

    private boolean columnExists(Connection connection, String table, String column) throws SQLException
    {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet result = metadata.getColumns(connection.getCatalog(), null, table, column))
        {
            if (result.next()) return true;
        }
        try (ResultSet result = metadata.getColumns(connection.getCatalog(), null,
            table.toUpperCase(Locale.ROOT), column.toUpperCase(Locale.ROOT)))
        {
            return result.next();
        }
    }
}
