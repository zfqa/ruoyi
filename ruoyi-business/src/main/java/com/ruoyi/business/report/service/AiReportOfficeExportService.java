package com.ruoyi.business.report.service;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.business.report.domain.AiReport;
import org.apache.poi.sl.usermodel.TextParagraph.TextAlign;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.springframework.stereotype.Service;

/** 将确定性结构化报告导出为可独立打开的Word与PowerPoint文件。 */
@Service
public class AiReportOfficeExportService
{
    private static final String FONT = "Microsoft YaHei";
    private static final Color NAVY = new Color(13, 71, 105);
    private static final List<String> EXPORT_ORDER = List.of(
        "source", "methodology", "data_scope", "executive_summary", "market_summary",
        "tianma_history", "tianma_product", "tianma_customer", "tianma_application",
        "maker_sections", "makers", "narrative_sources", "evidence", "quality");
    private static final Map<String, String> LABELS = labels();

    public void writeWord(AiReport report, OutputStream output) throws IOException
    {
        JSONObject root = parse(report);
        if (StructuredReportOfficeRenderer.supports(root))
        {
            StructuredReportOfficeRenderer.writeWord(report, root, output);
            return;
        }
        try (XWPFDocument document = new XWPFDocument())
        {
            XWPFParagraph title = document.createParagraph();
            title.setAlignment(ParagraphAlignment.CENTER);
            addRun(title, reportTitle(report, root), 22, true, "0D4769");
            XWPFParagraph subtitle = document.createParagraph();
            subtitle.setAlignment(ParagraphAlignment.CENTER);
            addRun(subtitle, "车载显示市场结构化分析报告", 11, false, "667085");
            addMetadataTable(document, report, root);
            for (String key : EXPORT_ORDER)
            {
                Object value = root.get(key);
                if (!empty(value)) renderWordNode(document, label(key), value, 1);
            }
            document.write(output);
        }
    }

    public void writePowerPoint(AiReport report, OutputStream output) throws IOException
    {
        JSONObject root = parse(report);
        if (StructuredReportOfficeRenderer.supports(root))
        {
            StructuredReportOfficeRenderer.writePowerPoint(report, root, output);
            return;
        }
        try (XMLSlideShow show = new XMLSlideShow())
        {
            show.setPageSize(new Dimension(960, 540));
            addTitleSlide(show, reportTitle(report, root), report);
            for (String key : EXPORT_ORDER)
            {
                Object value = root.get(key);
                if (empty(value)) continue;
                List<String> lines = new ArrayList<>();
                flatten(value, "", lines);
                if (lines.isEmpty()) lines.add("暂无数据");
                List<String> wrapped = new ArrayList<>();
                for (String line : lines) wrapped.addAll(wrap(line, 72));
                int page = 1;
                for (int from = 0; from < wrapped.size(); from += 11)
                {
                    int to = Math.min(from + 11, wrapped.size());
                    String title = label(key) + (wrapped.size() > 11 ? "（" + page++ + "）" : "");
                    addContentSlide(show, title, wrapped.subList(from, to));
                }
            }
            show.write(output);
        }
    }

    public String safeFileName(String value)
    {
        String name = value == null || value.isBlank() ? "车载市场分析报告" : value.trim();
        name = name.replaceAll("[\\\\/:*?\"<>|\\r\\n]+", "_");
        return name.length() > 80 ? name.substring(0, 80) : name;
    }

    private JSONObject parse(AiReport report)
    {
        if (report == null || report.getReportContent() == null || report.getReportContent().isBlank())
            throw new IllegalArgumentException("报告内容为空");
        try
        {
            JSONObject root = JSON.parseObject(report.getReportContent());
            if (root == null) throw new IllegalArgumentException("报告内容为空");
            return root;
        }
        catch (RuntimeException e)
        {
            throw new IllegalArgumentException("报告JSON格式无效，无法导出", e);
        }
    }

    private String reportTitle(AiReport report, JSONObject root)
    {
        String title = root.getString("title");
        return title == null || title.isBlank() ? safeFileName(report.getTaskName()) : title;
    }

    private void addMetadataTable(XWPFDocument document, AiReport report, JSONObject root)
    {
        XWPFTable table = document.createTable(4, 2);
        table.setStyleID("TableGrid");
        setCell(table, 0, 0, "报告任务"); setCell(table, 0, 1, text(report.getTaskName()));
        setCell(table, 1, 0, "报告类型"); setCell(table, 1, 1, text(report.getReportType()));
        setCell(table, 2, 0, "生成模式"); setCell(table, 2, 1, text(report.getGenerationMode()));
        setCell(table, 3, 0, "生成时间"); setCell(table, 3, 1, text(root.get("generated_at")));
    }

    private void setCell(XWPFTable table, int row, int column, String value)
    {
        XWPFParagraph paragraph = table.getRow(row).getCell(column).getParagraphs().get(0);
        addRun(paragraph, value, 10, column == 0, column == 0 ? "0D4769" : "344054");
    }

    private void renderWordNode(XWPFDocument document, String heading, Object value, int level)
    {
        if (value instanceof Map<?, ?> map)
        {
            addHeading(document, heading, level);
            List<Map.Entry<?, ?>> scalars = new ArrayList<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) if (scalar(entry.getValue())) scalars.add(entry);
            if (!scalars.isEmpty())
            {
                XWPFTable table = document.createTable(scalars.size(), 2);
                table.setStyleID("TableGrid");
                for (int i = 0; i < scalars.size(); i++)
                {
                    setCell(table, i, 0, label(String.valueOf(scalars.get(i).getKey())));
                    setCell(table, i, 1, text(scalars.get(i).getValue()));
                }
            }
            for (Map.Entry<?, ?> entry : map.entrySet())
                if (!scalar(entry.getValue()) && !empty(entry.getValue()))
                    renderWordNode(document, label(String.valueOf(entry.getKey())), entry.getValue(), Math.min(level + 1, 4));
            return;
        }
        if (value instanceof List<?> list)
        {
            addHeading(document, heading, level);
            int index = 0;
            for (Object item : list)
            {
                index++;
                if (scalar(item))
                {
                    XWPFParagraph paragraph = document.createParagraph();
                    paragraph.setIndentationLeft(360);
                    addRun(paragraph, "• " + text(item), 10, false, "344054");
                }
                else renderWordNode(document, "第 " + index + " 项", item, Math.min(level + 1, 4));
            }
            return;
        }
        XWPFParagraph paragraph = document.createParagraph();
        addRun(paragraph, heading + "：", 10, true, "0D4769");
        addRun(paragraph, text(value), 10, false, "344054");
    }

    private void addHeading(XWPFDocument document, String value, int level)
    {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingBefore(level == 1 ? 260 : 140);
        paragraph.setSpacingAfter(80);
        addRun(paragraph, value, level == 1 ? 16 : level == 2 ? 13 : 11, true, level == 1 ? "0D4769" : "18679B");
    }

    private XWPFRun addRun(XWPFParagraph paragraph, String value, int size, boolean bold, String color)
    {
        XWPFRun run = paragraph.createRun();
        run.setText(value == null ? "" : value);
        run.setFontFamily(FONT); run.setFontSize(size); run.setBold(bold); run.setColor(color);
        return run;
    }

    private void addTitleSlide(XMLSlideShow show, String titleText, AiReport report)
    {
        XSLFSlide slide = show.createSlide();
        XSLFTextBox title = slide.createTextBox();
        title.setAnchor(new Rectangle2D.Double(72, 145, 816, 150));
        styleText(title, titleText, 28, true, NAVY, TextAlign.CENTER);
        XSLFTextBox subtitle = slide.createTextBox();
        subtitle.setAnchor(new Rectangle2D.Double(120, 315, 720, 80));
        styleText(subtitle, "车载显示市场结构化分析报告\n" + text(report.getTaskName()), 14, false, Color.DARK_GRAY, TextAlign.CENTER);
    }

    private void addContentSlide(XMLSlideShow show, String titleText, List<String> lines)
    {
        XSLFSlide slide = show.createSlide();
        XSLFTextBox title = slide.createTextBox();
        title.setAnchor(new Rectangle2D.Double(52, 28, 856, 55));
        styleText(title, titleText, 22, true, NAVY, TextAlign.LEFT);
        XSLFTextBox body = slide.createTextBox();
        body.setAnchor(new Rectangle2D.Double(65, 95, 830, 405));
        body.clearText();
        for (int i = 0; i < lines.size(); i++)
        {
            XSLFTextParagraph paragraph = body.addNewTextParagraph();
            paragraph.setSpaceAfter(7d);
            XSLFTextRun run = paragraph.addNewTextRun();
            run.setText("• " + lines.get(i)); run.setFontFamily(FONT); run.setFontSize(13d); run.setFontColor(Color.DARK_GRAY);
        }
    }

    private void styleText(XSLFTextBox box, String value, double size, boolean bold, Color color, TextAlign align)
    {
        box.clearText();
        XSLFTextParagraph paragraph = box.addNewTextParagraph();
        paragraph.setTextAlign(align);
        XSLFTextRun run = paragraph.addNewTextRun();
        run.setText(value); run.setFontFamily(FONT); run.setFontSize(size); run.setBold(bold); run.setFontColor(color);
    }

    private void flatten(Object value, String path, List<String> target)
    {
        if (value instanceof Map<?, ?> map)
        {
            for (Map.Entry<?, ?> entry : map.entrySet())
            {
                String next = path.isBlank() ? label(String.valueOf(entry.getKey())) : path + " / " + label(String.valueOf(entry.getKey()));
                flatten(entry.getValue(), next, target);
            }
        }
        else if (value instanceof List<?> list)
        {
            for (int i = 0; i < list.size(); i++) flatten(list.get(i), path + " [" + (i + 1) + "]", target);
        }
        else target.add((path.isBlank() ? "内容" : path) + "：" + text(value));
    }

    private List<String> wrap(String value, int width)
    {
        List<String> result = new ArrayList<>();
        String remaining = value == null ? "" : value;
        if (remaining.isEmpty()) return List.of("暂无数据");
        while (remaining.length() > width)
        {
            result.add(remaining.substring(0, width));
            remaining = "  " + remaining.substring(width);
        }
        result.add(remaining);
        return result;
    }

    private boolean scalar(Object value)
    {
        return value == null || value instanceof String || value instanceof Number || value instanceof Boolean;
    }

    private boolean empty(Object value)
    {
        return value == null || value instanceof String text && text.isBlank()
            || value instanceof Map<?, ?> map && map.isEmpty() || value instanceof List<?> list && list.isEmpty();
    }

    private String text(Object value)
    {
        if (value == null) return "--";
        if (value instanceof JSONObject || value instanceof JSONArray) return JSON.toJSONString(value);
        return String.valueOf(value);
    }

    private String label(String key)
    {
        return LABELS.getOrDefault(key, key.replace('_', ' '));
    }

    private static Map<String, String> labels()
    {
        Map<String, String> map = new LinkedHashMap<>();
        map.put("methodology", "数据口径与计算说明"); map.put("data_scope", "数据范围");
        map.put("executive_summary", "管理层摘要"); map.put("market_summary", "Y25前三季度总览");
        map.put("tianma_history", "Tianma前装出货、面积及市占率"); map.put("tianma_product", "Tianma增长点分析一：产品线");
        map.put("tianma_customer", "Tianma增长点分析二：客户/区域"); map.put("tianma_application", "Tianma增长点分析三：应用");
        map.put("maker_sections", "各厂商结构化分析"); map.put("makers", "厂商洞察");
        map.put("narrative_sources", "洞察结论引用来源"); map.put("evidence", "指标证据"); map.put("quality", "报告质量说明");
        map.put("insights", "分析结论"); map.put("rows", "数据表"); map.put("summary_matrix", "产品线汇总矩阵");
        map.put("shipment", "出货量"); map.put("display_area", "出货面积"); map.put("shipment_share", "出货市占率");
        map.put("display_area_share", "面积市占率"); map.put("technology_history", "技术别出货");
        map.put("size_distribution", "尺寸分布"); map.put("technology_size_growth", "技术尺寸增长");
        map.put("scope", "筛选口径"); map.put("formulas", "计算公式"); map.put("notes", "口径说明"); map.put("limitations", "限制说明");
        map.put("data_gaps", "数据缺口"); map.put("warnings", "生成提示"); map.put("source", "数据来源");
        map.put("maker", "厂商"); map.put("title", "标题"); map.put("periods", "期间数据"); map.put("values", "数值");
        return Map.copyOf(map);
    }
}
