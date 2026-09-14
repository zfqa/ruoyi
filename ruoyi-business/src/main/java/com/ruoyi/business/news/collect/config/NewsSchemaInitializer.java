package com.ruoyi.business.news.collect.config;

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

/**
 * POC启动时使用RuoYi现有DataSource幂等补齐新闻采集表。
 * 不读取第二套数据库配置，也不要求运维人员再次输入数据库密码。
 */
@Component
public class NewsSchemaInitializer implements InitializingBean
{
    private final DataSource dataSource;
    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public NewsSchemaInitializer(DataSource dataSource,
        @Value("${business.news.schema-auto-init:true}") boolean enabled)
    {
        this.dataSource = dataSource;
        this.jdbc = new JdbcTemplate(dataSource);
        this.enabled = enabled;
    }

    @Override
    public void afterPropertiesSet() throws SQLException
    {
        if (!enabled) return;
        createArticleTables();
        ensureCollectColumns();
    }

    private void createArticleTables()
    {
        jdbc.execute("""
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
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='官网新闻采集业务明细'
            """);
        jdbc.execute("""
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
            ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='新闻采集任务与文章关系'
            """);
    }

    private void ensureCollectColumns() throws SQLException
    {
        Map<String, String> columns = new LinkedHashMap<>();
        columns.put("source_name", "varchar(100) DEFAULT '' COMMENT 'Python新闻源名称'");
        columns.put("trigger_type", "varchar(16) DEFAULT 'MANUAL' COMMENT 'MANUAL/SCHEDULED'");
        columns.put("publish_time_start", "varchar(32) DEFAULT NULL COMMENT '发布时间起始'");
        columns.put("publish_time_end", "varchar(32) DEFAULT NULL COMMENT '发布时间结束'");
        columns.put("force_flag", "char(1) DEFAULT '0' COMMENT '是否强制采集'");
        columns.put("started_time", "datetime DEFAULT NULL");
        columns.put("completed_time", "datetime DEFAULT NULL");
        columns.put("fetched_count", "int NOT NULL DEFAULT 0");
        columns.put("inserted_count", "int NOT NULL DEFAULT 0");
        columns.put("updated_count", "int NOT NULL DEFAULT 0");
        columns.put("duplicate_count", "int NOT NULL DEFAULT 0");
        columns.put("filtered_count", "int NOT NULL DEFAULT 0");
        columns.put("failed_count", "int NOT NULL DEFAULT 0");
        columns.put("mysql_inserted_count", "int NOT NULL DEFAULT 0");
        columns.put("mysql_updated_count", "int NOT NULL DEFAULT 0");
        columns.put("mysql_existing_count", "int NOT NULL DEFAULT 0");
        columns.put("statistics_version", "varchar(16) DEFAULT NULL");
        columns.put("crawl_run_id", "varchar(64) DEFAULT NULL");
        columns.put("error_message", "varchar(500) DEFAULT ''");

        try (Connection connection = dataSource.getConnection())
        {
            if (!tableExists(connection, "business_news_collect"))
                throw new SQLException("原数据库缺少business_news_collect，请先执行项目基础SQL");
            for (Map.Entry<String, String> entry : columns.entrySet())
            {
                if (!columnExists(connection, "business_news_collect", entry.getKey()))
                    jdbc.execute("ALTER TABLE business_news_collect ADD COLUMN " + entry.getKey() + " " + entry.getValue());
            }
        }
    }

    private boolean tableExists(Connection connection, String table) throws SQLException
    {
        DatabaseMetaData metadata = connection.getMetaData();
        try (ResultSet result = metadata.getTables(connection.getCatalog(), null, table, new String[] {"TABLE"}))
        {
            if (result.next()) return true;
        }
        try (ResultSet result = metadata.getTables(connection.getCatalog(), null,
            table.toUpperCase(Locale.ROOT), new String[] {"TABLE"}))
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
