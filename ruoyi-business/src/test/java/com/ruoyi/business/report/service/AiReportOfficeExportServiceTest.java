package com.ruoyi.business.report.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.stream.Collectors;
import com.ruoyi.business.report.domain.AiReport;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTextShape;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

class AiReportOfficeExportServiceTest
{
    private final AiReportOfficeExportService service = new AiReportOfficeExportService();

    @Test
    void exportsTraceableReportToReadableWordAndPowerPoint() throws Exception
    {
        AiReport report = sampleReport();
        ByteArrayOutputStream wordBytes = new ByteArrayOutputStream();
        service.writeWord(report, wordBytes);
        assertTrue(wordBytes.size() > 1000);
        try (XWPFDocument word = new XWPFDocument(new ByteArrayInputStream(wordBytes.toByteArray())))
        {
            String paragraphs = word.getParagraphs().stream().map(p -> p.getText()).collect(Collectors.joining("\n"));
            String tables = word.getTables().stream().map(t -> t.getText()).collect(Collectors.joining("\n"));
            assertTrue(paragraphs.contains("竞争社对标分析"));
            assertTrue(paragraphs.contains("Tianma增长来自LTPS"));
            assertTrue(tables.contains("19.6%"));
            assertTrue(paragraphs.contains("洞察结论引用来源"));
        }

        ByteArrayOutputStream pptBytes = new ByteArrayOutputStream();
        service.writePowerPoint(report, pptBytes);
        assertTrue(pptBytes.size() > 1000);
        try (XMLSlideShow ppt = new XMLSlideShow(new ByteArrayInputStream(pptBytes.toByteArray())))
        {
            String text = ppt.getSlides().stream().flatMap(slide -> slide.getShapes().stream())
                .filter(XSLFTextShape.class::isInstance).map(XSLFTextShape.class::cast)
                .map(XSLFTextShape::getText).collect(Collectors.joining("\n"));
            String tables = ppt.getSlides().stream().flatMap(slide -> slide.getShapes().stream())
                .filter(XSLFTable.class::isInstance).map(XSLFTable.class::cast)
                .flatMap(table -> table.getRows().stream()).flatMap(row -> row.getCells().stream())
                .map(cell -> cell.getText()).collect(Collectors.joining("\n"));
            assertTrue(ppt.getSlides().size() >= 4);
            assertTrue(ppt.getPictureData().size() >= 4);
            assertTrue(text.contains("竞争社对标分析"));
            assertTrue(text.contains("前装出货、面积及市占率") || text.contains("前装出货 面积及市占率"));
            assertTrue(tables.contains("19.6%") || text.contains("19.6"));
            assertTrue(text.contains("Tianma增长来自LTPS"));
            assertTrue(tables.contains("S1"));
        }
    }

    @Test
    void validatesJsonAndSanitizesDownloadName()
    {
        assertEquals("报告_终稿_", service.safeFileName("报告/终稿?"));
        AiReport invalid = new AiReport(); invalid.setReportContent("not-json");
        assertThrows(IllegalArgumentException.class, () -> service.writeWord(invalid, new ByteArrayOutputStream()));
    }

    private AiReport sampleReport()
    {
        AiReport report = new AiReport();
        report.setId(8L); report.setTaskName("车载市场报告"); report.setStatus("2");
        report.setReportType("competitive_insight"); report.setGenerationMode("python_metrics_llm_narrative");
        report.setReportContent("""
            {
              "title":"竞争社对标分析 - Tianma、AUO、CSOT、BOE",
              "generated_at":"2026-09-12T12:00:00Z",
              "methodology":{"notes":["所有指标由Python计算"],"scope":{"current_year":2025,"prior_year":2024,"full_year":false,"period_order":["Y22","Y23","Y24","Y25F","Y25Q1-Q3"]}},
              "executive_summary":["Tianma增长来自LTPS [S1]"],
              "market_summary":{"rows":[{"values":{"2024":100,"2025":119.6},"yoy_2025_vs_2024":0.196}]},
              "maker_sections":{"Tianma":{"history":{
                "shipment":{"periods":{"Y22":28602,"Y23":29591,"Y24":36938,"Y25F":42123,"Y25Q1-Q3":31622},"yoy_periods":{"Y23":0.035,"Y24":0.248,"Y25F":0.14,"Y25Q1-Q3":0.196}},
                "display_area":{"periods":{"Y22":486530,"Y23":547970,"Y24":809238,"Y25F":1001200,"Y25Q1-Q3":742892},"yoy_periods":{"Y23":0.126,"Y24":0.477,"Y25F":0.237,"Y25Q1-Q3":0.316}},
                "shipment_share":{"periods":{"Y22":0.167,"Y23":0.161,"Y24":0.181,"Y25F":0.194,"Y25Q1-Q3":0.196}},
                "display_area_share":{"periods":{"Y22":0.141,"Y23":0.133,"Y24":0.163,"Y25F":0.174,"Y25Q1-Q3":0.178}}
              }}},
              "narrative_sources":[{"citation_label":"S1","source_file":"Shipment.xlsx"}],
              "evidence":[{"metric_id":"tianma.shipment","sheet":"Shipment","cells":["A10"]}],
              "quality":{"source_grounded":true,"data_gaps":[]}
            }
            """);
        return report;
    }
}
