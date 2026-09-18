package com.ruoyi.business.report.service;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.geom.Rectangle2D;
import java.io.IOException;
import java.io.OutputStream;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.business.report.domain.AiReport;
import org.apache.poi.sl.usermodel.TableCell.BorderEdge;
import org.apache.poi.sl.usermodel.TextParagraph.TextAlign;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFSlide;
import org.apache.poi.xslf.usermodel.XSLFTable;
import org.apache.poi.xslf.usermodel.XSLFTableCell;
import org.apache.poi.xslf.usermodel.XSLFTableRow;
import org.apache.poi.xslf.usermodel.XSLFTextBox;
import org.apache.poi.xslf.usermodel.XSLFTextParagraph;
import org.apache.poi.xslf.usermodel.XSLFTextRun;
import org.apache.poi.xwpf.usermodel.ParagraphAlignment;
import org.apache.poi.xwpf.usermodel.TableRowAlign;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;

/**
 * 把车载市场结构化报告按前端业务语义导出，避免将数MB的证据JSON机械展开。
 * 数值直接读取确定性指标；LLM文案只作为带引用的洞察展示。
 */
final class StructuredReportOfficeRenderer
{
    private static final String FONT = "Microsoft YaHei";
    private static final Color NAVY = new Color(0x0D, 0x47, 0x69);
    private static final Color BLUE = new Color(0x18, 0x67, 0x9B);
    private static final Color PALE = new Color(0xF2, 0xF7, 0xFA);
    private static final Color GRID = new Color(0xD9, 0xE1, 0xE8);
    private static final List<String> BASE_PERIODS = List.of("Y22", "Y23", "Y24", "Y25F", "Y25Q1-Q3");
    private static final ThreadLocal<List<String>> ACTIVE_PERIODS = ThreadLocal.withInitial(() -> BASE_PERIODS);
    private static final ThreadLocal<Boolean> FULL_YEAR = ThreadLocal.withInitial(() -> Boolean.FALSE);
    private static final List<String> DEFAULT_MAKERS = List.of("Tianma", "AUO", "CSOT", "BOE");
    private static final DecimalFormat NUMBER = new DecimalFormat("#,##0.##");

    private StructuredReportOfficeRenderer() {}

    private static List<String> periods()
    {
        return ACTIVE_PERIODS.get();
    }

    private static boolean fullYear()
    {
        return Boolean.TRUE.equals(FULL_YEAR.get());
    }

    private static void bindPeriods(JSONObject root)
    {
        boolean fy = detectFullYear(root);
        FULL_YEAR.set(fy);
        ACTIVE_PERIODS.set(resolvePeriods(root));
    }

    private static void clearPeriods()
    {
        ACTIVE_PERIODS.remove();
        FULL_YEAR.remove();
    }

    private static List<String> resolvePeriods(JSONObject root)
    {
        LinkedHashSet<String> ordered = new LinkedHashSet<>(BASE_PERIODS);
        JSONObject methodology = root == null ? null : root.getJSONObject("methodology");
        JSONObject scope = methodology == null ? null : methodology.getJSONObject("scope");
        JSONArray focus = scope == null ? null : scope.getJSONArray("focus_periods");
        if (focus != null)
        {
            for (Object item : focus)
            {
                if (item != null && !String.valueOf(item).isBlank())
                {
                    ordered.add(String.valueOf(item));
                }
            }
        }
        JSONObject sections = makerSections(root);
        for (String maker : sections.keySet())
        {
            JSONObject detail = sections.getJSONObject(maker);
            JSONObject history = detail == null ? null : detail.getJSONObject("history");
            JSONObject historyScope = history == null ? null : history.getJSONObject("scope");
            JSONArray order = historyScope == null ? null : historyScope.getJSONArray("period_order");
            if (order != null)
            {
                for (Object item : order)
                {
                    if (item != null && !String.valueOf(item).isBlank())
                    {
                        ordered.add(String.valueOf(item));
                    }
                }
            }
        }
        Integer throughYear = scope == null ? null : scope.getInteger("data_through_year");
        Integer throughQuarter = scope == null ? null : scope.getInteger("data_through_quarter");
        if (throughYear == null || throughQuarter == null)
        {
            return new ArrayList<>(ordered);
        }
        List<String> clipped = new ArrayList<>();
        for (String key : ordered)
        {
            if (withinDataThrough(key, throughYear, throughQuarter))
            {
                clipped.add(key);
            }
        }
        return clipped.isEmpty() ? new ArrayList<>(ordered) : clipped;
    }

    private static boolean withinDataThrough(String key, int throughYear, int throughQuarter)
    {
        if (key == null || key.isBlank())
        {
            return false;
        }
        String text = key.trim().toUpperCase();
        if (BASE_PERIODS.contains(key) || text.endsWith("F") || text.contains("Q1-Q3"))
        {
            Integer year = periodYear(text);
            return year == null || year <= throughYear;
        }
        Integer year = periodYear(text);
        Integer quarter = periodQuarter(text);
        if (year == null)
        {
            return true;
        }
        if (quarter == null)
        {
            return year <= throughYear;
        }
        if (year < throughYear)
        {
            return true;
        }
        if (year > throughYear)
        {
            return false;
        }
        return quarter <= throughQuarter;
    }

    private static Integer periodYear(String text)
    {
        if (text == null || text.length() < 3 || text.charAt(0) != 'Y')
        {
            return null;
        }
        String body = text.substring(1);
        int end = 0;
        while (end < body.length() && Character.isDigit(body.charAt(end)))
        {
            end++;
        }
        if (end == 0)
        {
            return null;
        }
        return 2000 + Integer.parseInt(body.substring(0, end));
    }

    private static Integer periodQuarter(String text)
    {
        int index = text.indexOf('Q');
        if (index < 0 || index + 1 >= text.length())
        {
            return null;
        }
        if (text.contains("Q1-Q3"))
        {
            return null;
        }
        char digit = text.charAt(index + 1);
        if (!Character.isDigit(digit))
        {
            return null;
        }
        return digit - '0';
    }

    private static boolean detectFullYear(JSONObject root)
    {
        if (root == null)
        {
            return false;
        }
        JSONObject methodology = root.getJSONObject("methodology");
        JSONObject scope = methodology == null ? null : methodology.getJSONObject("scope");
        if (scope != null)
        {
            if (Boolean.TRUE.equals(scope.getBoolean("full_year"))
                || Boolean.TRUE.equals(scope.getBoolean("full_year_2025")))
            {
                return true;
            }
            String horizon = scope.getString("report_horizon");
            if (horizon != null && horizon.toLowerCase().endsWith("_full_year"))
            {
                return true;
            }
            if ("y25_full_year".equalsIgnoreCase(horizon))
            {
                return true;
            }
            if (Boolean.TRUE.equals(scope.getBoolean("has_y26q1")))
            {
                return true;
            }
            JSONArray focus = scope.getJSONArray("focus_periods");
            if (focus != null)
            {
                for (Object item : focus)
                {
                    String key = String.valueOf(item);
                    if (key.startsWith("Y26") || key.startsWith("Y27"))
                    {
                        return true;
                    }
                }
            }
        }
        String title = root.getString("title");
        return title != null && title.contains("全年");
    }

    static boolean supports(JSONObject root)
    {
        return root != null && root.getJSONObject("market_summary") != null
            && (root.getJSONObject("maker_sections") != null || root.getJSONObject("tianma_history") != null);
    }

    static void writeWord(AiReport report, JSONObject root, OutputStream output) throws IOException
    {
        bindPeriods(root);
        try (XWPFDocument document = new XWPFDocument())
        {
            wordTitle(document, reportTitle(report, root));
            wordSubtitle(document, fullYear()
                ? "车载显示市场结构化分析报告（Y25全年）"
                : "车载显示市场结构化分析报告（Y25前三季度）");
            wordMetadata(document, report, root);
            wordNarratives(document, "管理层摘要", root.getJSONArray("executive_summary"));
            wordMarketSummary(document, root);
            JSONObject sections = makerSections(root);
            for (String maker : orderedMakers(root, sections))
            {
                JSONObject detail = sections.getJSONObject(maker);
                if (detail != null) wordMaker(document, maker, detail);
            }
            wordSources(document, root);
            wordQuality(document, root);
            document.write(output);
        }
        finally
        {
            clearPeriods();
        }
    }

    static void writePowerPoint(AiReport report, JSONObject root, OutputStream output) throws IOException
    {
        bindPeriods(root);
        try (XMLSlideShow show = new XMLSlideShow())
        {
            show.setPageSize(new Dimension(960, 540));
            pptTitle(show, reportTitle(report, root), report.getTaskName());
            addPptNarrativeSlides(show, "管理层摘要", strings(root.getJSONArray("executive_summary")));
            pptMarketSummary(show, root);
            JSONObject sections = makerSections(root);
            for (String maker : orderedMakers(root, sections))
            {
                JSONObject detail = sections.getJSONObject(maker);
                if (detail != null) pptMaker(show, maker, detail);
            }
            pptSources(show, root);
            pptQuality(show, root);
            show.write(output);
        }
        finally
        {
            clearPeriods();
        }
    }

    private static void wordTitle(XWPFDocument document, String value)
    {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setStyle("Title"); paragraph.setAlignment(ParagraphAlignment.CENTER);
        wordRun(paragraph, value, 22, true, "000000");
    }

    private static void wordSubtitle(XWPFDocument document, String value)
    {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setAlignment(ParagraphAlignment.CENTER); paragraph.setSpacingAfter(220);
        wordRun(paragraph, value, 11, false, "667085");
    }

    private static void wordMetadata(XWPFDocument document, AiReport report, JSONObject root)
    {
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[] {"报告任务", safe(report.getTaskName()), "报告类型", safe(report.getReportType())});
        rows.add(new String[] {"生成模式", safe(report.getGenerationMode()), "生成时间", safe(root.getString("generated_at"))});
        wordTable(document, new String[] {"项目", "内容", "项目", "内容"}, rows, 9);
    }

    private static void wordMarketSummary(XWPFDocument document, JSONObject root)
    {
        wordHeading(document, fullYear() ? "Y25全年总览" : "Y25前三季度总览", 1);
        JSONObject summary = root.getJSONObject("market_summary");
        JSONArray marketRows = summary == null ? null : summary.getJSONArray("rows");
        List<String[]> totalRows = new ArrayList<>();
        if (marketRows != null)
        {
            for (Object item : marketRows)
            {
                JSONObject metric = object(item);
                totalRows.add(new String[] {"市场合计", metricValue(metric, "2024"), metricValue(metric, "2025"), pct(metric.get("yoy_2025_vs_2024"), 1)});
            }
        }
        if (!totalRows.isEmpty())
        {
            wordTable(document, fullYear()
                ? new String[] {"范围", "Y24全年（千片）", "Y25全年（千片）", "同比"}
                : new String[] {"范围", "Y24 Q1-Q3（千片）", "Y25 Q1-Q3（千片）", "同比"}, totalRows, 9);
        }
        JSONObject matrix = summary == null ? null : summary.getJSONObject("summary_matrix");
        JSONArray rows = matrix == null ? null : matrix.getJSONArray("rows");
        if (rows != null && !rows.isEmpty())
        {
            List<String> makers = orderedMakers(root, makerSections(root));
            wordHeading(document, "市场产品线", 2);
            wordTable(document, new String[] {"产品线", "市场YoY", "市场细分占比"}, marketSummaryRows(rows), 9);
            for (String maker : makers)
            {
                wordHeading(document, maker + " 产品线", 2);
                wordTable(document, new String[] {"产品线", "YoY", "内部占比", "细分市场占比"}, makerSummaryRows(rows, maker), 9);
            }
        }
        wordNarratives(document, "总览结论", summary == null ? null : summary.getJSONArray("insights"));
    }

    private static List<String[]> summaryRows(JSONArray rows, List<String> makers)
    {
        List<String[]> result = new ArrayList<>();
        for (Object item : rows)
        {
            JSONObject row = object(item); JSONObject market = row.getJSONObject("market");
            List<String> cells = new ArrayList<>();
            cells.add(safe(row.getString("label")));
            cells.add(pct(market == null ? null : market.get("yoy_2025_vs_2024"), 0));
            cells.add(pct(valueAt(market, "total_market_share", "2025"), 0));
            JSONObject makerValues = row.getJSONObject("makers");
            for (String maker : makers)
            {
                JSONObject metric = makerValues == null ? null : makerValues.getJSONObject(maker);
                cells.add(pct(metric == null ? null : metric.get("yoy_2025_vs_2024"), 0));
                cells.add(pct(valueAt(metric, "internal_share", "2025"), 0));
                cells.add(pct(valueAt(metric, "same_size_market_share", "2025"), 1));
            }
            result.add(cells.toArray(String[]::new));
        }
        return result;
    }

    private static List<String[]> marketSummaryRows(JSONArray rows)
    {
        List<String[]> result = new ArrayList<>();
        for (Object item : rows)
        {
            JSONObject row = object(item);
            JSONObject market = row.getJSONObject("market");
            result.add(new String[] {
                safe(row.getString("label")),
                pct(market == null ? null : market.get("yoy_2025_vs_2024"), 0),
                pct(valueAt(market, "total_market_share", "2025"), 0)
            });
        }
        return result;
    }

    private static List<String[]> makerSummaryRows(JSONArray rows, String maker)
    {
        List<String[]> result = new ArrayList<>();
        for (Object item : rows)
        {
            JSONObject row = object(item);
            JSONObject makerValues = row.getJSONObject("makers");
            JSONObject metric = makerValues == null ? null : makerValues.getJSONObject(maker);
            result.add(new String[] {
                safe(row.getString("label")),
                pct(metric == null ? null : metric.get("yoy_2025_vs_2024"), 0),
                pct(valueAt(metric, "internal_share", "2025"), 0),
                pct(valueAt(metric, "same_size_market_share", "2025"), 1)
            });
        }
        return result;
    }

    private static void wordMaker(XWPFDocument document, String maker, JSONObject detail)
    {
        wordHeading(document, maker + " 洞察", 1);
        JSONObject history = detail.getJSONObject("history");
        if (history != null)
        {
            wordHeading(document, maker + "前装出货 面积及市占率", 2);
            wordNarratives(document, "分析结论", flattenInsights(history.getJSONObject("insights")));
            List<String[]> rows = new ArrayList<>();
            addHistoryRow(rows, "Shipment（千片）", history.getJSONObject("shipment"), false);
            addHistoryRow(rows, "Display area（m²）", history.getJSONObject("display_area"), false);
            addHistoryRow(rows, "Shipment share", history.getJSONObject("shipment_share"), true);
            addHistoryRow(rows, "Display area share", history.getJSONObject("display_area_share"), true);
            if (!rows.isEmpty()) wordTable(document, historyHeaders(), rows, 8);
        }
        JSONObject product = detail.getJSONObject("product");
        if (product != null) wordProduct(document, maker, product);
        JSONObject customer = detail.getJSONObject("customer");
        if (customer != null) wordCustomer(document, maker, customer);
        JSONObject application = detail.getJSONObject("application");
        if (application != null) wordApplication(document, maker, application);
    }

    private static void wordProduct(XWPFDocument document, String maker, JSONObject product)
    {
        wordHeading(document, maker + "增长点分析一 产品线", 2);
        wordNarratives(document, "分析结论", flattenInsights(product.getJSONObject("insights")));
        JSONObject technology = product.getJSONObject("technology_history");
        List<String[]> technologyRows = new ArrayList<>();
        for (String name : List.of("LTPS", "a-Si"))
        {
            JSONObject metric = technology == null ? null : technology.getJSONObject(name);
            if (metric != null) technologyRows.add(periodRow(name, metric, false));
        }
        if (!technologyRows.isEmpty()) wordTable(document, periodHeaders("技术"), technologyRows, 8);
        JSONObject distribution = product.getJSONObject("size_distribution");
        if (distribution == null) distribution = product.getJSONObject("y25q1_q3_size_distribution");
        JSONArray points = distribution == null ? null : distribution.getJSONArray("points");
        if (points != null && !points.isEmpty())
        {
            List<JSONObject> sorted = objects(points); sorted.sort(Comparator.comparingDouble(v -> doubleValue(v.get("size"))));
            List<String[]> rows = new ArrayList<>();
            for (JSONObject point : sorted) rows.add(new String[] {number(point.get("size")), number(point.get("shipment"))});
            wordHeading(document, maker + (fullYear() ? " Y25全年尺寸别分布" : " Y25 Q1-Q3尺寸别分布"), 3);
            wordTable(document, new String[] {"Size（英寸）", "Shipment（千片）"}, rows, 9);
        }
        JSONObject growth = product.getJSONObject("technology_size_growth");
        if (growth != null)
        {
            List<String[]> rows = new ArrayList<>();
            for (String technologyName : List.of("LTPS", "a-Si"))
            {
                JSONObject metric = growth.getJSONObject(technologyName);
                JSONArray series = metric == null ? null : metric.getJSONArray("series");
                if (series != null) for (Object item : series) rows.add(periodRow(technologyName + " " + safe(object(item).getString("label")), object(item), false));
            }
            if (!rows.isEmpty())
            {
                wordHeading(document, maker + " 技术尺寸别增长", 3);
                wordTable(document, periodHeaders("产品线"), rows, 8);
            }
        }
    }

    private static void wordCustomer(XWPFDocument document, String maker, JSONObject customer)
    {
        wordHeading(document, maker + "增长点分析二 客户与区域", 2);
        wordNarratives(document, "分析结论", flattenInsights(customer.getJSONObject("insights")));
        JSONObject top = customer.getJSONObject("top_clients");
        JSONArray clients = top == null ? null : top.getJSONArray("clients");
        if (clients != null && !clients.isEmpty())
        {
            List<String[]> rows = new ArrayList<>();
            for (Object item : clients)
            {
                JSONObject client = object(item); JSONObject periods = client.getJSONObject("periods");
                if (fullYear())
                {
                    rows.add(new String[] {safe(client.getString("client")), value(periods, "Y22"), value(periods, "Y23"), value(periods, "Y24"),
                        value(periods, "Y25F"), value(periods, "Y25Q1-Q3"),
                        pct(primaryNumber(client, "yoy_primary", "yoy_2025_full_vs_2024_full", "yoy_2025_q1_q3_vs_2024_q1_q3"), 0),
                        pct(primaryNumber(client, "share_primary", "share_y25_full", "share_y25_q1_q3"), 0),
                        pct(primaryNumber(client, "growth_contribution_primary", "growth_contribution_y25_full", "growth_contribution_y25_q1_q3"), 0)});
                }
                else
                {
                    rows.add(new String[] {safe(client.getString("client")), value(periods, "Y22"), value(periods, "Y23"), value(periods, "Y24"),
                        value(periods, "Y25Q1-Q3"),
                        pct(client.get("yoy_2025_q1_q3_vs_2024_q1_q3"), 0), pct(client.get("share_y25_q1_q3"), 0), pct(client.get("growth_contribution_y25_q1_q3"), 0)});
                }
            }
            if (fullYear())
            {
                wordTable(document, new String[] {"客户", "Y22", "Y23", "Y24", "Y25F", "Y25 Q1-Q3", "同比", "内部占比", "增长贡献"}, rows, 7);
            }
            else
            {
                wordTable(document, new String[] {"客户", "Y22", "Y23", "Y24", "Y25 Q1-Q3", "同比", "内部占比", "增长贡献"}, rows, 8);
            }
        }
        JSONObject regions = customer.getJSONObject("regions"); JSONArray regionRows = regions == null ? null : regions.getJSONArray("rows");
        if (regionRows != null && !regionRows.isEmpty())
        {
            List<String[]> rows = new ArrayList<>();
            for (Object item : regionRows)
            {
                JSONObject region = object(item); JSONObject annual = region.getJSONObject("annual");
                JSONObject q = region.getJSONObject("q1_q3");
                JSONObject fy = region.getJSONObject("full_year");
                if (fullYear() && fy != null)
                {
                    rows.add(new String[] {safe(region.getString("region")), value(annual, "Y24"), pct(annual == null ? null : annual.get("yoy_2024_vs_2023"), 0),
                        value(fy, "Y25"), pct(fy.get("yoy_2025_vs_2024"), 0)});
                }
                else
                {
                    rows.add(new String[] {safe(region.getString("region")), value(annual, "Y24"), pct(annual == null ? null : annual.get("yoy_2024_vs_2023"), 0),
                        value(q, "Y25Q1-Q3"), pct(q == null ? null : q.get("yoy_2025_vs_2024"), 0)});
                }
            }
            wordTable(document, new String[] {"区域", "Y24出货量", "Y24同比",
                fullYear() ? "Y25全年" : "Y25 Q1-Q3",
                fullYear() ? "全年同比" : "前三季度同比"}, rows, 8);
        }
    }

    private static void wordApplication(XWPFDocument document, String maker, JSONObject application)
    {
        wordHeading(document, maker + "增长点分析三 应用", 2);
        wordNarratives(document, "分析结论", flattenInsights(application.getJSONObject("insights")));
        JSONObject history = application.getJSONObject("application_history"); JSONArray series = history == null ? null : history.getJSONArray("series");
        if (series != null && !series.isEmpty())
        {
            List<String[]> rows = new ArrayList<>();
            for (Object item : series)
            {
                JSONObject metric = object(item); String[] base = periodRow(safe(metric.getString("application")), metric, false);
                String[] row = new String[base.length + 2]; System.arraycopy(base, 0, row, 0, base.length);
                row[base.length] = pct(primaryNumber(metric, "share_primary", "share_y25_full", "share_y25_q1_q3"), 0);
                row[base.length + 1] = pct(primaryNumber(metric, "growth_contribution_primary", "growth_contribution_y25_full", "growth_contribution_y25_q1_q3"), 0);
                rows.add(row);
            }
            List<String> headers = new ArrayList<>(List.of(periodHeaders("应用")));
            headers.add(fullYear() ? "Y25全年占比" : "Y25占比");
            headers.add("增长贡献");
            wordTable(document, headers.toArray(String[]::new), rows, 8);
        }
        JSONObject sizes = application.getJSONObject("key_sizes"); JSONArray sizeRows = sizes == null ? null : sizes.getJSONArray("rows");
        if (sizeRows != null && !sizeRows.isEmpty())
        {
            List<String[]> rows = new ArrayList<>();
            for (Object item : sizeRows)
            {
                JSONObject row = object(item);
                rows.add(new String[] {safe(row.getString("application")), number(row.get("size")), safe(row.getString("technology")), number(row.get("shipment")),
                    pct(row.get("share"), 0), pct(primaryNumber(row, "yoy_primary", "yoy_2025_q1_q3_vs_2024_q1_q3"), 0)});
            }
            wordHeading(document, maker + " 应用别重点尺寸 " + (fullYear() ? "Y25全年" : "Y25 Q1-Q3") + "出货占比", 3);
            wordTable(document, new String[] {"应用", "尺寸", "技术", "出货量", "占比", "同比"}, rows, 8);
        }
    }

    private static void wordSources(XWPFDocument document, JSONObject root)
    {
        JSONArray sources = root.getJSONArray("narrative_sources");
        if (sources == null || sources.isEmpty()) return;
        wordHeading(document, "洞察结论引用来源", 1);
        List<String[]> rows = new ArrayList<>();
        for (Object item : sources)
        {
            JSONObject source = object(item);
            rows.add(new String[] {"[" + safe(source.getString("citation_label")) + "]", safe(source.getString("conclusion")), safe(source.getString("source_file")), metricIds(source)});
        }
        wordTable(document, new String[] {"引用", "结论", "源文件", "指标ID"}, rows, 8);
    }

    private static void wordQuality(XWPFDocument document, JSONObject root)
    {
        JSONObject quality = root.getJSONObject("quality");
        if (quality == null) return;
        wordNarratives(document, "数据缺口", quality.getJSONArray("data_gaps"));
        wordNarratives(document, "生成提示", quality.getJSONArray("warnings"));
    }

    private static void wordNarratives(XWPFDocument document, String heading, JSONArray values)
    {
        wordNarratives(document, heading, strings(values));
    }

    private static void wordNarratives(XWPFDocument document, String heading, List<String> values)
    {
        if (values == null || values.isEmpty()) return;
        wordHeading(document, heading, 3);
        for (String value : values)
        {
            XWPFParagraph paragraph = document.createParagraph(); paragraph.setIndentationLeft(240); paragraph.setSpacingAfter(50);
            wordRun(paragraph, "• " + value, 10, false, "344054");
        }
    }

    private static void wordHeading(XWPFDocument document, String value, int level)
    {
        XWPFParagraph paragraph = document.createParagraph();
        paragraph.setSpacingBefore(level == 1 ? 260 : 150); paragraph.setSpacingAfter(70);
        keepWithNext(paragraph);
        wordRun(paragraph, value, level == 1 ? 16 : level == 2 ? 13 : 11, true, "000000");
    }

    private static XWPFRun wordRun(XWPFParagraph paragraph, String value, int size, boolean bold, String color)
    {
        XWPFRun run = paragraph.createRun(); run.setText(safe(value)); run.setFontFamily(FONT);
        run.setFontSize(size); run.setBold(bold); run.setColor(color); return run;
    }

    private static void wordTable(XWPFDocument document, String[] headers, List<String[]> rows, int fontSize)
    {
        if (rows == null || rows.isEmpty()) return;
        XWPFTable table = document.createTable(1, headers.length); table.setStyleID("TableGrid"); table.setTableAlignment(TableRowAlign.CENTER);
        for (int column = 0; column < headers.length; column++) wordCell(table.getRow(0).getCell(column), headers[column], fontSize, true, "0D4769", "FFFFFF");
        for (XWPFTableCell cell : table.getRow(0).getTableCells()) keepWithNext(cell.getParagraphs().get(0));
        table.getRow(0).getCtRow().addNewTrPr().addNewTblHeader().setVal(org.openxmlformats.schemas.wordprocessingml.x2006.main.STOnOff.TRUE);
        int rowIndex = 0;
        for (String[] values : rows)
        {
            XWPFTableCell[] cells = table.createRow().getTableCells().toArray(XWPFTableCell[]::new);
            for (int column = 0; column < headers.length; column++)
                wordCell(cells[column], column < values.length ? values[column] : "--", fontSize, false, rowIndex % 2 == 1 ? "F2F7FA" : "FFFFFF", "344054");
            rowIndex++;
        }
        document.createParagraph().setSpacingAfter(80);
    }

    private static void keepWithNext(XWPFParagraph paragraph)
    {
        var properties = paragraph.getCTP().isSetPPr() ? paragraph.getCTP().getPPr() : paragraph.getCTP().addNewPPr();
        properties.addNewKeepNext().setVal(org.openxmlformats.schemas.wordprocessingml.x2006.main.STOnOff.TRUE);
    }

    private static void wordCell(XWPFTableCell cell, String value, int fontSize, boolean bold, String fill, String color)
    {
        cell.getCTTc().addNewTcPr().addNewShd().setFill(fill);
        XWPFParagraph paragraph = cell.getParagraphs().get(0); paragraph.setAlignment(ParagraphAlignment.CENTER);
        paragraph.setSpacingBefore(25); paragraph.setSpacingAfter(25); wordRun(paragraph, value, fontSize, bold, color);
    }

    private static void pptTitle(XMLSlideShow show, String titleText, String taskName)
    {
        XSLFSlide slide = show.createSlide();
        pptText(slide, titleText, 70, 150, 820, 120, 30, true, NAVY, TextAlign.CENTER);
        pptText(slide, "车载显示市场结构化分析报告\n" + safe(taskName), 120, 310, 720, 80, 15, false, Color.DARK_GRAY, TextAlign.CENTER);
    }

    private static void pptMarketSummary(XMLSlideShow show, JSONObject root)
    {
        JSONObject summary = root.getJSONObject("market_summary");
        JSONArray rows = summary == null ? null : summary.getJSONArray("rows");
        List<String[]> market = new ArrayList<>();
        if (rows != null) for (Object item : rows)
        {
            JSONObject metric = object(item);
            market.add(new String[] {"市场合计", metricValue(metric, "2024"), metricValue(metric, "2025"), pct(metric.get("yoy_2025_vs_2024"), 1)});
        }
        addPptTableSlides(show, fullYear() ? "Y25全年市场总览" : "Y25前三季度市场总览",
            fullYear()
                ? new String[] {"范围", "Y24全年（千片）", "Y25全年（千片）", "同比"}
                : new String[] {"范围", "Y24 Q1-Q3（千片）", "Y25 Q1-Q3（千片）", "同比"}, market,
            strings(summary == null ? null : summary.getJSONArray("insights")), 10);
        JSONObject matrix = summary == null ? null : summary.getJSONObject("summary_matrix"); JSONArray matrixRows = matrix == null ? null : matrix.getJSONArray("rows");
        if (matrixRows != null && !matrixRows.isEmpty())
        {
            List<String> makers = orderedMakers(root, makerSections(root));
            List<String> headers = new ArrayList<>(List.of("产品线", "市场 YoY", "市场占比"));
            for (String maker : makers) headers.addAll(List.of(maker + " YoY", maker + " 内部", maker + " 市占"));
            addPptTableSlides(show, fullYear() ? "Y25全年产品线 Summary" : "Y25前三季度产品线 Summary",
                headers.toArray(String[]::new), summaryRows(matrixRows, makers), List.of(), 7);
        }
    }

    private static void pptMaker(XMLSlideShow show, String maker, JSONObject detail)
    {
        JSONObject history = detail.getJSONObject("history");
        if (history != null)
        {
            List<String[]> rows = new ArrayList<>();
            addHistoryRow(rows, "Shipment（千片）", history.getJSONObject("shipment"), false);
            addHistoryRow(rows, "Display area（m²）", history.getJSONObject("display_area"), false);
            addHistoryRow(rows, "Shipment share", history.getJSONObject("shipment_share"), true);
            addHistoryRow(rows, "Display area share", history.getJSONObject("display_area_share"), true);
            addPptTableSlides(show, maker + "前装出货 面积及市占率", historyHeaders(), rows, flattenInsights(history.getJSONObject("insights")), 9);
        }
        JSONObject product = detail.getJSONObject("product"); if (product != null) pptProduct(show, maker, product);
        JSONObject customer = detail.getJSONObject("customer"); if (customer != null) pptCustomer(show, maker, customer);
        JSONObject application = detail.getJSONObject("application"); if (application != null) pptApplication(show, maker, application);
    }

    private static void pptProduct(XMLSlideShow show, String maker, JSONObject product)
    {
        JSONObject technology = product.getJSONObject("technology_history"); List<String[]> rows = new ArrayList<>();
        for (String name : List.of("LTPS", "a-Si")) { JSONObject metric = technology == null ? null : technology.getJSONObject(name); if (metric != null) rows.add(periodRow(name, metric, false)); }
        addPptTableSlides(show, maker + "增长点分析一 产品线", periodHeaders("技术"), rows, flattenInsights(product.getJSONObject("insights")), 9);
        JSONObject distribution = product.getJSONObject("size_distribution");
        if (distribution == null) distribution = product.getJSONObject("y25q1_q3_size_distribution");
        JSONArray points = distribution == null ? null : distribution.getJSONArray("points");
        if (points != null && !points.isEmpty())
        {
            String[] headers = new String[] {"Size", "Shipment", "Size", "Shipment", "Size", "Shipment", "Size", "Shipment"};
            addPptTableSlides(show, maker + (fullYear() ? " Y25全年尺寸别分布" : " Y25 Q1-Q3尺寸别分布"), headers, sizeGridRows(points, 4), List.of(), 9);
        }
        JSONObject growth = product.getJSONObject("technology_size_growth");
        if (growth != null)
        {
            for (String name : List.of("LTPS", "a-Si"))
            {
                JSONObject metric = growth.getJSONObject(name); JSONArray series = metric == null ? null : metric.getJSONArray("series"); List<String[]> values = new ArrayList<>();
                if (series != null) for (Object item : series) values.add(periodRow(safe(object(item).getString("label")), object(item), false));
                addPptTableSlides(show, maker + " " + name + " 尺寸别增长", periodHeaders("尺寸"), values, List.of(), 9);
            }
        }
    }

    private static List<String[]> sizeGridRows(JSONArray points, int groups)
    {
        List<JSONObject> sorted = objects(points); sorted.sort(Comparator.comparingDouble(v -> doubleValue(v.get("size"))));
        int rowCount = (int) Math.ceil(sorted.size() / (double) groups); List<String[]> result = new ArrayList<>();
        for (int row = 0; row < rowCount; row++)
        {
            String[] values = new String[groups * 2];
            for (int group = 0; group < groups; group++)
            {
                int index = group * rowCount + row;
                if (index < sorted.size()) { values[group * 2] = number(sorted.get(index).get("size")); values[group * 2 + 1] = number(sorted.get(index).get("shipment")); }
                else { values[group * 2] = ""; values[group * 2 + 1] = ""; }
            }
            result.add(values);
        }
        return result;
    }

    private static void pptCustomer(XMLSlideShow show, String maker, JSONObject customer)
    {
        JSONObject top = customer.getJSONObject("top_clients"); JSONArray clients = top == null ? null : top.getJSONArray("clients"); List<String[]> clientRows = new ArrayList<>();
        if (clients != null) for (Object item : clients)
        {
            JSONObject client = object(item); JSONObject periods = client.getJSONObject("periods");
            if (fullYear())
            {
                clientRows.add(new String[] {safe(client.getString("client")), value(periods, "Y22"), value(periods, "Y23"), value(periods, "Y24"),
                    value(periods, "Y25F"), value(periods, "Y25Q1-Q3"),
                    pct(primaryNumber(client, "yoy_primary", "yoy_2025_full_vs_2024_full", "yoy_2025_q1_q3_vs_2024_q1_q3"), 0),
                    pct(primaryNumber(client, "share_primary", "share_y25_full", "share_y25_q1_q3"), 0),
                    pct(primaryNumber(client, "growth_contribution_primary", "growth_contribution_y25_full", "growth_contribution_y25_q1_q3"), 0)});
            }
            else
            {
                clientRows.add(new String[] {safe(client.getString("client")), value(periods, "Y22"), value(periods, "Y23"), value(periods, "Y24"), value(periods, "Y25Q1-Q3"),
                    pct(client.get("yoy_2025_q1_q3_vs_2024_q1_q3"), 0), pct(client.get("share_y25_q1_q3"), 0), pct(client.get("growth_contribution_y25_q1_q3"), 0)});
            }
        }
        addPptTableSlides(show, maker + "增长点分析二 客户",
            fullYear()
                ? new String[] {"客户", "Y22", "Y23", "Y24", "Y25F", "Y25 Q1-Q3", "同比", "内部占比", "增长贡献"}
                : new String[] {"客户", "Y22", "Y23", "Y24", "Y25 Q1-Q3", "同比", "内部占比", "增长贡献"},
            clientRows, flattenInsights(customer.getJSONObject("insights")), 8);
        JSONObject regions = customer.getJSONObject("regions"); JSONArray regionRows = regions == null ? null : regions.getJSONArray("rows"); List<String[]> values = new ArrayList<>();
        if (regionRows != null) for (Object item : regionRows)
        {
            JSONObject region = object(item); JSONObject annual = region.getJSONObject("annual");
            JSONObject q = region.getJSONObject("q1_q3");
            JSONObject fy = region.getJSONObject("full_year");
            if (fullYear() && fy != null)
            {
                values.add(new String[] {safe(region.getString("region")), value(annual, "Y24"), pct(annual == null ? null : annual.get("yoy_2024_vs_2023"), 0),
                    value(fy, "Y25"), pct(fy.get("yoy_2025_vs_2024"), 0)});
            }
            else
            {
                values.add(new String[] {safe(region.getString("region")), value(annual, "Y24"), pct(annual == null ? null : annual.get("yoy_2024_vs_2023"), 0),
                    value(q, "Y25Q1-Q3"), pct(q == null ? null : q.get("yoy_2025_vs_2024"), 0)});
            }
        }
        addPptTableSlides(show, maker + "区域别占比情况",
            new String[] {"区域", "Y24出货量", "Y24同比", fullYear() ? "Y25全年" : "Y25 Q1-Q3", fullYear() ? "全年同比" : "前三季度同比"},
            values, List.of(), 9);
    }

    private static void pptApplication(XMLSlideShow show, String maker, JSONObject application)
    {
        JSONObject history = application.getJSONObject("application_history"); JSONArray series = history == null ? null : history.getJSONArray("series"); List<String[]> rows = new ArrayList<>();
        if (series != null) for (Object item : series)
        {
            JSONObject metric = object(item); String[] base = periodRow(safe(metric.getString("application")), metric, false); String[] row = new String[base.length + 2];
            System.arraycopy(base, 0, row, 0, base.length);
            row[base.length] = pct(primaryNumber(metric, "share_primary", "share_y25_full", "share_y25_q1_q3"), 0);
            row[base.length + 1] = pct(primaryNumber(metric, "growth_contribution_primary", "growth_contribution_y25_full", "growth_contribution_y25_q1_q3"), 0);
            rows.add(row);
        }
        List<String> headers = new ArrayList<>(List.of(periodHeaders("应用")));
        headers.add(fullYear() ? "Y25全年占比" : "Y25占比");
        headers.add("增长贡献");
        addPptTableSlides(show, maker + "增长点分析三 应用", headers.toArray(String[]::new), rows, flattenInsights(application.getJSONObject("insights")), 8);
        JSONObject sizes = application.getJSONObject("key_sizes"); JSONArray sizeRows = sizes == null ? null : sizes.getJSONArray("rows"); List<String[]> values = new ArrayList<>();
        if (sizeRows != null) for (Object item : sizeRows)
        {
            JSONObject row = object(item); values.add(new String[] {safe(row.getString("application")), number(row.get("size")), safe(row.getString("technology")),
                number(row.get("shipment")), pct(row.get("share"), 0), pct(primaryNumber(row, "yoy_primary", "yoy_2025_q1_q3_vs_2024_q1_q3"), 0)});
        }
        addPptTableSlides(show, maker + "应用别重点尺寸" + (fullYear() ? "（Y25全年）" : "（Y25 Q1-Q3）"),
            new String[] {"应用", "尺寸", "技术", "出货量", "占比", "同比"}, values, List.of(), 9);
    }

    private static void pptSources(XMLSlideShow show, JSONObject root)
    {
        JSONArray sources = root.getJSONArray("narrative_sources"); List<String[]> rows = new ArrayList<>();
        if (sources != null) for (Object item : sources)
        {
            JSONObject source = object(item); rows.add(new String[] {"[" + safe(source.getString("citation_label")) + "]", safe(source.getString("conclusion")), safe(source.getString("source_file")), metricIds(source)});
        }
        addPptTableSlides(show, "洞察结论引用来源", new String[] {"引用", "结论", "源文件", "指标ID"}, rows, List.of(), 8);
    }

    private static void pptQuality(XMLSlideShow show, JSONObject root)
    {
        JSONObject quality = root.getJSONObject("quality"); if (quality == null) return;
        List<String> lines = new ArrayList<>();
        for (String gap : strings(quality.getJSONArray("data_gaps"))) lines.add("数据缺口：" + gap);
        for (String warning : strings(quality.getJSONArray("warnings"))) lines.add("生成提示：" + warning);
        addPptNarrativeSlides(show, "报告质量说明", lines);
    }

    private static void addPptNarrativeSlides(XMLSlideShow show, String title, List<String> lines)
    {
        if (lines == null || lines.isEmpty()) return;
        for (int from = 0, page = 1; from < lines.size(); from += 8, page++)
        {
            int to = Math.min(from + 8, lines.size()); XSLFSlide slide = show.createSlide();
            pptText(slide, title + (lines.size() > 8 ? "（" + page + "）" : ""), 48, 26, 864, 48, 24, true, NAVY, TextAlign.LEFT);
            double y = 98;
            for (String line : lines.subList(from, to)) { pptText(slide, "• " + line, 70, y, 820, 44, 15, false, Color.DARK_GRAY, TextAlign.LEFT); y += 50; }
            pptFooter(slide);
        }
    }

    private static void addPptTableSlides(XMLSlideShow show, String title, String[] headers, List<String[]> rows, List<String> insights, int fontSize)
    {
        if ((rows == null || rows.isEmpty()) && (insights == null || insights.isEmpty())) return;
        List<String[]> safeRows = rows == null ? List.of() : rows; int chunkSize = headers.length >= 8 ? 13 : 9;
        if (safeRows.isEmpty()) { addPptNarrativeSlides(show, title, insights); return; }
        for (int from = 0, page = 1; from < safeRows.size(); from += chunkSize, page++)
        {
            int to = Math.min(from + chunkSize, safeRows.size()); XSLFSlide slide = show.createSlide();
            pptText(slide, title + (safeRows.size() > chunkSize ? "（" + page + "）" : ""), 44, 22, 872, 46, 23, true, NAVY, TextAlign.LEFT);
            double tableY = 88;
            if (from == 0 && insights != null && !insights.isEmpty())
            {
                List<String> visible = insights.subList(0, Math.min(3, insights.size())); double y = 70;
                for (String insight : visible) { pptText(slide, "• " + insight, 55, y, 850, 27, 10.5, false, Color.DARK_GRAY, TextAlign.LEFT); y += 29; }
                tableY = y + 8;
            }
            pptTable(slide, headers, safeRows.subList(from, to), tableY, fontSize);
            pptFooter(slide);
        }
    }

    private static void pptTable(XSLFSlide slide, String[] headers, List<String[]> rows, double y, int fontSize)
    {
        double x = 42, width = 876, rowHeight = Math.min(34, Math.max(24, (485 - y) / (rows.size() + 1)));
        XSLFTable table = slide.createTable(); table.setAnchor(new Rectangle2D.Double(x, y, width, rowHeight * (rows.size() + 1)));
        XSLFTableRow header = table.addRow(); header.setHeight(rowHeight);
        for (String value : headers) pptCell(header.addCell(), value, fontSize, true, NAVY, Color.WHITE);
        int rowIndex = 0;
        for (String[] values : rows)
        {
            XSLFTableRow row = table.addRow(); row.setHeight(rowHeight);
            for (int column = 0; column < headers.length; column++) pptCell(row.addCell(), column < values.length ? values[column] : "--", fontSize, false, rowIndex % 2 == 1 ? PALE : Color.WHITE, Color.DARK_GRAY);
            rowIndex++;
        }
        double first = headers.length > 1 ? Math.min(150, width * 0.20) : width;
        table.setColumnWidth(0, first);
        double remaining = headers.length > 1 ? (width - first) / (headers.length - 1) : width;
        for (int i = 1; i < headers.length; i++) table.setColumnWidth(i, remaining);
    }

    private static void pptCell(XSLFTableCell cell, String value, int fontSize, boolean bold, Color fill, Color color)
    {
        cell.setFillColor(fill); cell.setLeftInset(3); cell.setRightInset(3); cell.setTopInset(2); cell.setBottomInset(2);
        for (BorderEdge edge : BorderEdge.values()) { cell.setBorderColor(edge, GRID); cell.setBorderWidth(edge, 0.6); }
        cell.clearText(); XSLFTextParagraph paragraph = cell.addNewTextParagraph(); paragraph.setTextAlign(TextAlign.CENTER);
        XSLFTextRun run = paragraph.addNewTextRun(); run.setText(safe(value)); run.setFontFamily(FONT); run.setFontSize((double) fontSize); run.setBold(bold); run.setFontColor(color);
    }

    private static void pptText(XSLFSlide slide, String value, double x, double y, double w, double h, double fontSize, boolean bold, Color color, TextAlign align)
    {
        XSLFTextBox box = slide.createTextBox(); box.setAnchor(new Rectangle2D.Double(x, y, w, h)); box.clearText();
        XSLFTextParagraph paragraph = box.addNewTextParagraph(); paragraph.setTextAlign(align);
        XSLFTextRun run = paragraph.addNewTextRun(); run.setText(safe(value)); run.setFontFamily(FONT); run.setFontSize(fontSize); run.setBold(bold); run.setFontColor(color);
    }

    private static void pptFooter(XSLFSlide slide)
    {
        pptText(slide, "数据来源与指标证据见报告引用页", 48, 512, 864, 18, 8.5, false, new Color(0x98, 0xA2, 0xB3), TextAlign.RIGHT);
    }

    private static void addHistoryRow(List<String[]> rows, String label, JSONObject metric, boolean percent)
    {
        if (metric == null) return;
        List<String> keys = periods();
        String[] row = new String[keys.size() + 2];
        row[0] = label;
        JSONObject periodValues = metric.getJSONObject("periods");
        for (int i = 0; i < keys.size(); i++)
        {
            row[i + 1] = percent
                ? pct(periodValues == null ? null : periodValues.get(keys.get(i)), 1)
                : value(periodValues, keys.get(i));
        }
        row[row.length - 1] = pct(metric.get("standard_y25f_yoy"), 1);
        rows.add(row);
    }

    private static String[] historyHeaders()
    {
        List<String> keys = periods();
        List<String> headers = new ArrayList<>();
        headers.add("指标");
        for (String key : keys)
        {
            headers.add(periodLabel(key));
        }
        headers.add("Y25F YoY");
        return headers.toArray(String[]::new);
    }

    private static String[] periodHeaders(String first)
    {
        List<String> headers = new ArrayList<>();
        headers.add(first);
        for (String key : periods())
        {
            headers.add(periodLabel(key));
        }
        return headers.toArray(String[]::new);
    }

    private static String periodLabel(String key)
    {
        if ("Y25Q1-Q3".equals(key))
        {
            return "Y25 Q1-Q3";
        }
        if ("Y25F".equals(key))
        {
            return "Y25F(全年)";
        }
        return key;
    }

    private static String[] periodRow(String label, JSONObject metric, boolean percent)
    {
        List<String> keys = periods();
        String[] row = new String[keys.size() + 1];
        row[0] = label;
        JSONObject periodValues = metric.getJSONObject("periods");
        for (int i = 0; i < keys.size(); i++)
        {
            row[i + 1] = percent
                ? pct(periodValues == null ? null : periodValues.get(keys.get(i)), 1)
                : value(periodValues, keys.get(i));
        }
        return row;
    }

    private static JSONObject makerSections(JSONObject root)
    {
        JSONObject sections = root.getJSONObject("maker_sections");
        if (sections != null && !sections.isEmpty()) return sections;
        JSONObject legacy = new JSONObject(); JSONObject detail = new JSONObject();
        detail.put("history", root.getJSONObject("tianma_history")); detail.put("product", root.getJSONObject("tianma_product"));
        detail.put("customer", root.getJSONObject("tianma_customer")); detail.put("application", root.getJSONObject("tianma_application")); legacy.put("Tianma", detail); return legacy;
    }

    private static List<String> orderedMakers(JSONObject root, JSONObject sections)
    {
        Set<String> result = new LinkedHashSet<>(); JSONObject methodology = root.getJSONObject("methodology"); JSONObject scope = methodology == null ? null : methodology.getJSONObject("scope");
        JSONArray configured = scope == null ? null : scope.getJSONArray("makers"); if (configured != null) for (Object item : configured) if (item != null) result.add(String.valueOf(item));
        for (String maker : DEFAULT_MAKERS) if (sections.containsKey(maker)) result.add(maker);
        result.addAll(sections.keySet()); return new ArrayList<>(result);
    }

    private static List<String> flattenInsights(JSONObject insights)
    {
        List<String> result = new ArrayList<>(); if (insights == null) return result;
        for (Object value : insights.values())
        {
            if (value instanceof JSONArray array) result.addAll(strings(array));
            else if (value instanceof List<?> list) for (Object item : list) if (item != null) result.add(String.valueOf(item));
            else if (value != null) result.add(String.valueOf(value));
        }
        return result;
    }

    private static String reportTitle(AiReport report, JSONObject root)
    {
        String title = root.getString("title"); return title == null || title.isBlank() ? safe(report.getTaskName()) : title;
    }

    private static String metricValue(JSONObject metric, String period)
    {
        JSONObject values = metric == null ? null : metric.getJSONObject("values"); return value(values, period);
    }

    private static Object valueAt(JSONObject parent, String child, String key)
    {
        JSONObject object = parent == null ? null : parent.getJSONObject(child); return object == null ? null : object.get(key);
    }

    private static String value(JSONObject object, String key) { return object == null ? "--" : number(object.get(key)); }
    private static String number(Object value) { return value instanceof Number number ? NUMBER.format(number.doubleValue()) : value == null ? "--" : safe(String.valueOf(value)); }
    private static Object primaryNumber(JSONObject object, String... keys)
    {
        if (object == null || keys == null) return null;
        for (String key : keys)
        {
            if (key == null || key.isBlank()) continue;
            Object value = object.get(key);
            if (value != null) return value;
        }
        return null;
    }
    private static String pct(Object value, int digits)
    {
        if (!(value instanceof Number number)) return "--";
        return new DecimalFormat(digits == 0 ? "0%" : "0." + "0".repeat(digits) + "%").format(number.doubleValue());
    }

    private static double doubleValue(Object value) { return value instanceof Number number ? number.doubleValue() : Double.MAX_VALUE; }

    private static String metricIds(JSONObject source)
    {
        JSONArray values = source.getJSONArray("metric_ids"); if (values == null || values.isEmpty()) return "--";
        List<String> ids = strings(values); return String.join("、", ids.subList(0, Math.min(4, ids.size())));
    }

    private static List<String> strings(JSONArray values)
    {
        List<String> result = new ArrayList<>(); if (values != null) for (Object value : values) if (value != null && !String.valueOf(value).isBlank()) result.add(String.valueOf(value)); return result;
    }

    private static List<JSONObject> objects(JSONArray values)
    {
        List<JSONObject> result = new ArrayList<>(); for (Object value : values) result.add(object(value)); return result;
    }

    private static JSONObject object(Object value)
    {
        return value instanceof JSONObject object ? object : new JSONObject((Map<String, Object>) value);
    }

    private static String safe(String value) { return value == null || value.isBlank() ? "--" : value; }
}
