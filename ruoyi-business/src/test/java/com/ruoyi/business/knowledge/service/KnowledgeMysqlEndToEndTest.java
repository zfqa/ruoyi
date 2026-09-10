package com.ruoyi.business.knowledge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import com.ruoyi.business.knowledge.domain.KnowledgeBase;
import com.ruoyi.business.knowledge.domain.KnowledgeChunk;
import com.ruoyi.business.knowledge.domain.KnowledgeIngestTask;
import com.ruoyi.business.knowledge.mapper.KnowledgeBaseMapper;
import com.ruoyi.business.report.service.IAiReportService;
import org.apache.ibatis.builder.xml.XMLMapperBuilder;
import org.apache.ibatis.datasource.pooled.PooledDataSource;
import org.apache.ibatis.io.Resources;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** 真实MySQL：PDF上传、切分、索引、检索和页码溯源全链路。 */
@EnabledIfEnvironmentVariable(named = "KB_TEST_MYSQL_URL", matches = ".+")
class KnowledgeMysqlEndToEndTest
{
    @Test
    void ingestsPdfAndRetrievesTraceablePageFromMysql() throws Exception
    {
        String adminUrl = System.getenv("KB_TEST_MYSQL_URL");
        String username = System.getenv().getOrDefault("KB_TEST_MYSQL_USER", "root");
        String password = System.getenv().getOrDefault("KB_TEST_MYSQL_PASSWORD", "");
        String schema = "kb_e2e_" + UUID.randomUUID().toString().replace("-", "");
        if (!schema.startsWith("kb_e2e_")) throw new IllegalStateException("拒绝使用非测试数据库");

        try (Connection admin = DriverManager.getConnection(adminUrl, username, password);
             Statement statement = admin.createStatement())
        {
            statement.execute("CREATE DATABASE `" + schema + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        }

        String schemaUrl = appendSchema(adminUrl, schema);
        try
        {
            createTables(schemaUrl, username, password);
            try (SqlSession session = sqlSession(schemaUrl, username, password).openSession(true))
            {
                KnowledgeBaseMapper mapper = session.getMapper(KnowledgeBaseMapper.class);
                KnowledgeBase source = new KnowledgeBase();
                source.setSourceCode("E2E-PDF-001"); source.setSourceName("Automotive market evidence.pdf");
                source.setSourceType("PDF"); source.setConfidentiality("INTERNAL"); source.setAllowedPurpose("integration-test");
                source.setAllowedRoleIds(""); source.setEnabled("1"); source.setStatus("0"); source.setCreateBy("test");
                mapper.insertKnowledgeBase(source);

                ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
                doAnswer(invocation -> { ((Runnable) invocation.getArgument(0)).run(); return null; })
                    .when(executor).execute(any(Runnable.class));
                KnowledgeIngestService service = new KnowledgeIngestService(mapper,
                    new KnowledgeFileStorage("target/knowledge-mysql-e2e"), mock(IAiReportService.class), executor, "");
                KnowledgeGraphService graphService = new KnowledgeGraphService(mapper);
                service.setGraphService(graphService);

                MockMultipartFile pdf = new MockMultipartFile("file", "automotive-evidence.pdf", "application/pdf",
                    new ByteArrayInputStream(pdfBytes("Tianma automotive shipment increased by 20 percent in 2025.")));
                KnowledgeIngestTask task = service.submitPdf(source.getId(), "v-e2e-1", pdf, "tester");
                assertEquals("0", task.getStatus());
                KnowledgeIngestTask completed = mapper.selectIngestTaskById(task.getId());
                assertEquals("2", completed.getStatus(), completed.getErrorMessage());

                List<KnowledgeChunk> results = service.search("Tianma automotive shipment", "PDF", List.of(), true, 10);
                assertFalse(results.isEmpty());
                KnowledgeChunk first = results.get(0);
                assertEquals("Automotive market evidence.pdf", first.getSourceName());
                assertEquals("automotive-evidence.pdf", first.getOriginalName());
                assertEquals("v-e2e-1", first.getVersionNo());
                assertEquals(1, first.getPageStart());
                assertTrue(first.getSourceSnippet().contains("Tianma automotive shipment"));

                KnowledgeBase newsSource = new KnowledgeBase();
                newsSource.setSourceCode("E2E-NEWS-001"); newsSource.setSourceName("Automotive news");
                newsSource.setSourceType("NEWS"); newsSource.setConfidentiality("INTERNAL");
                newsSource.setAllowedPurpose("integration-test"); newsSource.setAllowedRoleIds("");
                newsSource.setEnabled("1"); newsSource.setStatus("0"); newsSource.setCreateBy("test");
                mapper.insertKnowledgeBase(newsSource);
                service.setNewsIngestEnabled(true);
                KnowledgeIngestTask newsTask = service.submitNews(newsSource.getId(), "news-e2e-1",
                    "https://example.com/automotive", "Automotive Q3 news",
                    "2023 Q3 news: company: Tianma, model: Model 3, Shipment: 1200 pcs, quarterly report and automotive policy.", "tester");
                assertEquals("2", mapper.selectIngestTaskById(newsTask.getId()).getStatus());
                Map<String, Object> graph = graphService.graph("2023 Q3", "NEWS", null, List.of(), true, 100);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> nodes = (List<Map<String, Object>>) graph.get("nodes");
                Set<String> types = nodes.stream().map(node -> String.valueOf(node.get("entityType"))).collect(java.util.stream.Collectors.toSet());
                assertTrue(types.containsAll(Set.of("COMPANY", "MODEL", "SALES", "NEWS", "FINANCIAL", "POLICY")));
            }
        }
        finally
        {
            if (!schema.matches("kb_e2e_[0-9a-f]{32}")) throw new IllegalStateException("拒绝删除非测试数据库：" + schema);
            try (Connection admin = DriverManager.getConnection(adminUrl, username, password);
                 Statement statement = admin.createStatement())
            {
                statement.execute("DROP DATABASE `" + schema + "`");
            }
        }
    }

    private SqlSessionFactory sqlSession(String url, String username, String password) throws Exception
    {
        PooledDataSource dataSource = new PooledDataSource("com.mysql.cj.jdbc.Driver", url, username, password);
        Configuration configuration = new Configuration(new Environment("test", new JdbcTransactionFactory(), dataSource));
        configuration.addMapper(KnowledgeBaseMapper.class);
        try (InputStream mapperXml = Resources.getResourceAsStream("mapper/business/knowledge/KnowledgeBaseMapper.xml"))
        {
            new XMLMapperBuilder(mapperXml, configuration,
                "mapper/business/knowledge/KnowledgeBaseMapper.xml", configuration.getSqlFragments()).parse();
        }
        return new SqlSessionFactoryBuilder().build(configuration);
    }

    private void createTables(String url, String username, String password) throws Exception
    {
        try (Connection connection = DriverManager.getConnection(url, username, password);
             Statement statement = connection.createStatement())
        {
            statement.execute("CREATE TABLE business_knowledge (id bigint NOT NULL AUTO_INCREMENT, source_code varchar(100), source_name varchar(255) NOT NULL, source_type varchar(20) NOT NULL DEFAULT 'PDF', current_version_id bigint, owner_dept_id bigint, confidentiality varchar(20) NOT NULL DEFAULT 'INTERNAL', allowed_purpose varchar(500) DEFAULT '', allowed_role_ids varchar(500) DEFAULT '', enabled char(1) NOT NULL DEFAULT '1', status char(1) DEFAULT '0', create_by varchar(64) DEFAULT '', create_time datetime, update_by varchar(64) DEFAULT '', update_time datetime, remark varchar(500) DEFAULT '', PRIMARY KEY(id), UNIQUE KEY uk_source_code(source_code)) ENGINE=InnoDB");
            statement.execute("CREATE TABLE business_kb_version (id bigint NOT NULL AUTO_INCREMENT, source_id bigint NOT NULL, version_no varchar(64) NOT NULL, original_name varchar(255) DEFAULT '', stored_path varchar(500) DEFAULT '', source_url varchar(1000) DEFAULT '', content_sha256 char(64) NOT NULL, published_time datetime, fetched_time datetime, parser_version varchar(64) DEFAULT '', page_count int DEFAULT 0, chunk_count int DEFAULT 0, status char(1) NOT NULL DEFAULT '0', error_message varchar(1000) DEFAULT '', create_by varchar(64) DEFAULT '', create_time datetime, PRIMARY KEY(id), UNIQUE KEY uk_version_hash(source_id,content_sha256), UNIQUE KEY uk_version_no(source_id,version_no)) ENGINE=InnoDB");
            statement.execute("CREATE TABLE business_kb_ingest_task (id bigint NOT NULL AUTO_INCREMENT, source_id bigint NOT NULL, version_id bigint NOT NULL, status char(1) NOT NULL DEFAULT '0', progress int NOT NULL DEFAULT 0, current_stage varchar(100) DEFAULT '', chunk_count int DEFAULT 0, error_message varchar(1000) DEFAULT '', started_time datetime, finished_time datetime, create_by varchar(64) DEFAULT '', create_time datetime, update_time datetime, PRIMARY KEY(id)) ENGINE=InnoDB");
            statement.execute("CREATE TABLE business_kb_chunk (id bigint NOT NULL AUTO_INCREMENT, source_id bigint NOT NULL, version_id bigint NOT NULL, chunk_no int NOT NULL, title_path varchar(500) DEFAULT '', content longtext NOT NULL, source_snippet text, page_start int, page_end int, source_url varchar(1000) DEFAULT '', report_id bigint, metric_id varchar(200) DEFAULT '', evidence_json longtext, content_sha256 char(64) NOT NULL, token_count int DEFAULT 0, create_time datetime, PRIMARY KEY(id), UNIQUE KEY uk_chunk_no(version_id,chunk_no), FULLTEXT KEY ft_chunk_content(title_path,content) WITH PARSER ngram) ENGINE=InnoDB");
            statement.execute("CREATE TABLE business_kb_entity (id bigint NOT NULL AUTO_INCREMENT, entity_key varchar(300) NOT NULL, entity_name varchar(255) NOT NULL, entity_type varchar(30) NOT NULL, aliases varchar(1000) DEFAULT '', create_time datetime, update_time datetime, PRIMARY KEY(id), UNIQUE KEY uk_entity_key(entity_key)) ENGINE=InnoDB");
            statement.execute("CREATE TABLE business_kb_relation (id bigint NOT NULL AUTO_INCREMENT, from_entity_id bigint NOT NULL, to_entity_id bigint NOT NULL, relation_type varchar(60) NOT NULL, source_id bigint NOT NULL, version_id bigint NOT NULL, chunk_id bigint NOT NULL, period varchar(20) DEFAULT '', data_type varchar(20) NOT NULL, evidence_snippet varchar(1000) DEFAULT '', evidence_start int DEFAULT 0, evidence_end int DEFAULT 0, create_time datetime, PRIMARY KEY(id), UNIQUE KEY uk_relation(from_entity_id,to_entity_id,relation_type,chunk_id)) ENGINE=InnoDB");
        }
    }

    private String appendSchema(String adminUrl, String schema)
    {
        int query = adminUrl.indexOf('?');
        String base = query >= 0 ? adminUrl.substring(0, query) : adminUrl;
        String suffix = query >= 0 ? adminUrl.substring(query) : "?useUnicode=true&characterEncoding=utf8&useSSL=false&serverTimezone=Asia/Shanghai";
        return base.replaceAll("/+$", "") + "/" + schema + suffix;
    }

    private byte[] pdfBytes(String text) throws Exception
    {
        try (PDDocument document = new PDDocument(); ByteArrayOutputStream output = new ByteArrayOutputStream())
        {
            PDPage page = new PDPage(); document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page))
            {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 750); content.showText(text); content.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }
}
