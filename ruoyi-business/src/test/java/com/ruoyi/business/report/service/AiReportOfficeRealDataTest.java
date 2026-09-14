package com.ruoyi.business.report.service;

import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.stream.Collectors;
import com.ruoyi.business.report.domain.AiReport;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** 使用本机真实报告验证Office导出；默认跳过，显式传入数据库密码时执行。 */
class AiReportOfficeRealDataTest
{
    @Test
    void exportsLatestRealReportWithoutFlatteningRawEvidence() throws Exception
    {
        String password = System.getenv("MYSQL_PASSWORD");
        Assumptions.assumeTrue(password != null && !password.isBlank());
        AiReport report = latestReport(password);
        Assumptions.assumeTrue(report != null);
        Path output = Path.of("target", "report-export-verification"); Files.createDirectories(output);
        AiReportOfficeExportService service = new AiReportOfficeExportService();
        Path wordPath = output.resolve("latest-report.docx"); Path pptPath = output.resolve("latest-report.pptx");
        try (OutputStream stream = Files.newOutputStream(wordPath)) { service.writeWord(report, stream); }
        try (OutputStream stream = Files.newOutputStream(pptPath)) { service.writePowerPoint(report, stream); }

        assertTrue(Files.size(wordPath) > 20_000); assertTrue(Files.size(pptPath) > 30_000);
        try (InputStream stream = Files.newInputStream(wordPath); XWPFDocument word = new XWPFDocument(stream))
        {
            String tables = word.getTables().stream().map(table -> table.getText()).collect(Collectors.joining("\n"));
            assertTrue(tables.contains("31,622")); assertTrue(tables.contains("19.6%"));
            assertTrue(tables.contains("LTPS")); assertTrue(tables.contains("a-Si"));
        }
        try (InputStream stream = Files.newInputStream(pptPath); XMLSlideShow ppt = new XMLSlideShow(stream))
        {
            String tables = ppt.getSlides().stream().flatMap(slide -> slide.getShapes().stream())
                .filter(XSLFTable.class::isInstance).map(XSLFTable.class::cast)
                .flatMap(table -> table.getRows().stream()).flatMap(row -> row.getCells().stream())
                .map(cell -> cell.getText()).collect(Collectors.joining("\n"));
            assertTrue(ppt.getSlides().size() >= 20); assertTrue(tables.contains("31,622"));
            assertTrue(tables.contains("19.6%")); assertTrue(tables.contains("Tianma"));
        }
    }

    private AiReport latestReport(String password) throws Exception
    {
        String sql = "select id, task_name, status, report_type, generation_mode, report_content "
            + "from business_report where status='2' order by id desc limit 1";
        try (Connection connection = DriverManager.getConnection(
                "jdbc:mysql://127.0.0.1:3306/ry-vue?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai", "root", password);
             PreparedStatement statement = connection.prepareStatement(sql); ResultSet result = statement.executeQuery())
        {
            if (!result.next()) return null;
            AiReport report = new AiReport(); report.setId(result.getLong("id")); report.setTaskName(result.getString("task_name"));
            report.setStatus(result.getString("status")); report.setReportType(result.getString("report_type"));
            report.setGenerationMode(result.getString("generation_mode")); report.setReportContent(result.getString("report_content")); return report;
        }
    }
}
