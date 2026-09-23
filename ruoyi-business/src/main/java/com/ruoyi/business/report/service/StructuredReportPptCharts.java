package com.ruoyi.business.report.service;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;
import javax.imageio.ImageIO;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.apache.poi.sl.usermodel.PictureData.PictureType;
import org.apache.poi.xslf.usermodel.XMLSlideShow;
import org.apache.poi.xslf.usermodel.XSLFPictureData;
import org.apache.poi.xslf.usermodel.XSLFPictureShape;
import org.apache.poi.xslf.usermodel.XSLFSlide;

/**
 * Renders report-matching chart PNGs for competitive-insight PPT export.
 */
final class StructuredReportPptCharts
{
    private static final Color BAR = new Color(0x18, 0x67, 0x9B);
    private static final Color LINE = new Color(0xE6, 0x7E, 0x22);
    private static final Color GRID = new Color(0xE5, 0xEB, 0xF0);
    private static final Color AXIS = new Color(0x66, 0x72, 0x85);
    private static final Color[] SERIES = {
        new Color(0x18, 0x67, 0x9B), new Color(0xE6, 0x7E, 0x22), new Color(0x2A, 0x9D, 0x8F),
        new Color(0xE9, 0xC4, 0x6A), new Color(0xF4, 0xA2, 0x61), new Color(0x26, 0x4A, 0x53),
        new Color(0x8E, 0xCA, 0xE6), new Color(0x90, 0xBE, 0x6D)
    };
    private static final DecimalFormat INT = new DecimalFormat("#,##0");
    private static final DecimalFormat ONE = new DecimalFormat("0.0");

    private StructuredReportPptCharts() {}

    /**
     * Volume + area combo charts (one slide, 1×2).
     */
    static void addHistoryVolumeCharts(XMLSlideShow show, XSLFSlide slide, String maker, JSONObject history,
                                       List<String> periodKeys, List<String> periodLabels) throws IOException
    {
        if (history == null || periodKeys == null || periodKeys.isEmpty()) return;
        byte[] volume = comboChartPng(maker + "前装出货情况（Kpcs）", maker, periodLabels,
            metricValues(history.getJSONObject("shipment"), periodKeys, false),
            metricValues(history.getJSONObject("shipment"), periodKeys, true), false);
        byte[] area = comboChartPng(maker + "前装出货面积情况（㎡）", maker, periodLabels,
            metricValues(history.getJSONObject("display_area"), periodKeys, false),
            metricValues(history.getJSONObject("display_area"), periodKeys, true), true);
        place(show, slide, volume, 30, 55, 445, 430);
        place(show, slide, area, 485, 55, 445, 430);
    }

    /**
     * Combined shipment + area share lines (one slide) — matches sample report, avoids two blank twin charts.
     */
    static void addHistoryShareChart(XMLSlideShow show, XSLFSlide slide, String maker, JSONObject history,
                                     List<String> periodKeys, List<String> periodLabels) throws IOException
    {
        if (history == null || periodKeys == null || periodKeys.isEmpty()) return;
        List<SeriesData> series = new ArrayList<>();
        series.add(new SeriesData("出货量市占率", shareValues(history.getJSONObject("shipment_share"), periodKeys), false));
        series.add(new SeriesData("出货面积市占率", shareValues(history.getJSONObject("display_area_share"), periodKeys), false));
        place(show, slide, multiLinePng(maker + "前装市占率情况", periodLabels, series, true), 60, 55, 840, 430);
    }

    static void addTechnologyChart(XMLSlideShow show, XSLFSlide slide, String maker, JSONObject product,
                                   List<String> periodKeys, List<String> periodLabels, double x, double y, double w, double h) throws IOException
    {
        JSONObject technology = product == null ? null : product.getJSONObject("technology_history");
        if (technology == null) return;
        List<SeriesData> series = new ArrayList<>();
        series.add(new SeriesData("a-Si", metricValues(technology.getJSONObject("a-Si"), periodKeys, false), false));
        series.add(new SeriesData("LTPS", metricValues(technology.getJSONObject("LTPS"), periodKeys, false), false));
        series.add(new SeriesData("a-Si YoY", metricValues(technology.getJSONObject("a-Si"), periodKeys, true), true));
        series.add(new SeriesData("LTPS YoY", metricValues(technology.getJSONObject("LTPS"), periodKeys, true), true));
        place(show, slide, stackedComboPng(maker + " 技术别出货情况（Kpcs）", periodLabels, series), x, y, w, h);
    }

    static void addSizeDistributionChart(XMLSlideShow show, XSLFSlide slide, String maker, JSONObject product,
                                         double x, double y, double w, double h) throws IOException
    {
        if (product == null) return;
        JSONObject distribution = product.getJSONObject("size_distribution");
        if (distribution == null) distribution = product.getJSONObject("y25q1_q3_size_distribution");
        JSONArray points = distribution == null ? null : distribution.getJSONArray("points");
        if (points == null || points.isEmpty()) return;
        String title = distribution.getString("label");
        if (title == null || title.isBlank()) title = maker + " 尺寸别分布情况";
        if (!title.startsWith(maker)) title = maker + " " + title;
        place(show, slide, scatterPng(title, points), x, y, w, h);
    }

    static void addTechnologySizeChart(XMLSlideShow show, XSLFSlide slide, String maker, JSONObject product,
                                       String technology, List<String> periodKeys, List<String> periodLabels,
                                       double x, double y, double w, double h) throws IOException
    {
        JSONObject growth = product == null ? null : product.getJSONObject("technology_size_growth");
        JSONObject metric = growth == null ? null : growth.getJSONObject(technology);
        JSONArray seriesArr = metric == null ? null : metric.getJSONArray("series");
        if (seriesArr == null || seriesArr.isEmpty()) return;
        List<SeriesData> series = new ArrayList<>();
        for (Object item : seriesArr)
        {
            JSONObject row = (JSONObject) item;
            series.add(new SeriesData(safe(row.getString("label")), periodNumbers(row.getJSONObject("periods"), periodKeys, false), false));
        }
        place(show, slide, stackedBarPng(maker + " " + technology + " 尺寸别增长情况（Kpcs）", periodLabels, series), x, y, w, h);
    }

    static void addCustomerChart(XMLSlideShow show, XSLFSlide slide, String maker, JSONObject customer,
                                 List<String> periodKeys, List<String> periodLabels, boolean fullYear,
                                 String yearLabel, double x, double y, double w, double h) throws IOException
    {
        JSONObject top = customer == null ? null : customer.getJSONObject("top_clients");
        JSONArray clients = top == null ? null : top.getJSONArray("clients");
        if (clients == null || clients.isEmpty()) return;
        String title = maker + (fullYear
            ? " 客户年度别出货（" + yearLabel + "全年）（Kpcs）"
            : " 前三季度出货前六大客户年度别出货情况（Kpcs）");
        List<SeriesData> series = new ArrayList<>();
        for (Object item : clients)
        {
            JSONObject client = (JSONObject) item;
            series.add(new SeriesData(safe(client.getString("client")), periodNumbers(client.getJSONObject("periods"), periodKeys, false), false));
        }
        place(show, slide, multiLinePng(title, periodLabels, series, false), x, y, w, h);
    }

    static void addApplicationChart(XMLSlideShow show, XSLFSlide slide, String maker, JSONObject application,
                                    List<String> periodKeys, List<String> periodLabels, boolean fullYear,
                                    String yearLabel, double x, double y, double w, double h) throws IOException
    {
        JSONObject history = application == null ? null : application.getJSONObject("application_history");
        JSONArray seriesArr = history == null ? null : history.getJSONArray("series");
        if (seriesArr == null || seriesArr.isEmpty()) return;
        String title = maker + (fullYear
            ? " 应用别出货（含" + yearLabel + "全年）（Kpcs）"
            : " 应用别出货情况（Kpcs）");
        List<SeriesData> series = new ArrayList<>();
        for (Object item : seriesArr)
        {
            JSONObject row = (JSONObject) item;
            series.add(new SeriesData(safe(row.getString("application")), periodNumbers(row.getJSONObject("periods"), periodKeys, false), false));
        }
        place(show, slide, stackedBarPng(title, periodLabels, series), x, y, w, h);
    }

    private static void place(XMLSlideShow show, XSLFSlide slide, byte[] png, double x, double y, double w, double h) throws IOException
    {
        if (png == null || png.length == 0) return;
        XSLFPictureData data = show.addPicture(png, PictureType.PNG);
        XSLFPictureShape picture = slide.createPicture(data);
        picture.setAnchor(new Rectangle2D.Double(x, y, w, h));
    }

    private static Double[] metricValues(JSONObject metric, List<String> keys, boolean yoy)
    {
        if (metric == null) return new Double[keys.size()];
        JSONObject values = metric.getJSONObject(yoy ? "yoy_periods" : "periods");
        if (values == null && !yoy) values = metric;
        return periodNumbers(values, keys, yoy);
    }

    /** Share ratios in JSON are 0~1; chart axis is percent. */
    private static Double[] shareValues(JSONObject metric, List<String> keys)
    {
        Double[] ratios = metricValues(metric, keys, false);
        Double[] pct = new Double[ratios.length];
        for (int i = 0; i < ratios.length; i++)
        {
            if (ratios[i] == null) continue;
            double v = ratios[i];
            pct[i] = v <= 1.5 ? v * 100.0 : v;
        }
        return pct;
    }

    private static Double[] periodNumbers(JSONObject values, List<String> keys, boolean yoyAsPercent)
    {
        Double[] out = new Double[keys.size()];
        for (int i = 0; i < keys.size(); i++)
        {
            Object raw = values == null ? null : values.get(keys.get(i));
            Double parsed = asDoubleOrNull(raw);
            if (parsed == null) continue;
            out[i] = yoyAsPercent ? parsed * 100.0 : parsed;
        }
        return out;
    }

    private static Double asDoubleOrNull(Object raw)
    {
        if (raw instanceof Number number) return number.doubleValue();
        if (raw == null) return null;
        try
        {
            String text = String.valueOf(raw).trim().replace(",", "").replace("%", "");
            if (text.isEmpty() || "--".equals(text)) return null;
            return Double.parseDouble(text);
        }
        catch (Exception ignored)
        {
            return null;
        }
    }

    private static byte[] comboChartPng(String title, String maker, List<String> categories,
                                        Double[] bars, Double[] line, boolean areaStyle) throws IOException
    {
        int width = 900, height = 420;
        BufferedImage image = base(width, height);
        Graphics2D g = image.createGraphics();
        style(g);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, width, height);
        drawTitle(g, title, width);
        int left = 70, right = 70, top = 56, bottom = 70;
        int plotW = width - left - right, plotH = height - top - bottom;
        double maxBar = maxAbs(bars, 1);
        double maxLine = Math.max(10, maxAbs(line, 10));
        drawGrid(g, left, top, plotW, plotH, 4);
        drawCategories(g, categories, left, top + plotH, plotW);
        drawLeftAxis(g, left, top, plotH, maxBar, areaStyle);
        drawRightAxis(g, left + plotW, top, plotH, maxLine);
        int n = categories.size();
        double slot = plotW / (double) Math.max(1, n);
        double barW = Math.min(48, slot * 0.45);
        for (int i = 0; i < n; i++)
        {
            Double value = bars[i];
            if (value == null) continue;
            double bh = (value / maxBar) * plotH;
            double bx = left + slot * i + (slot - barW) / 2.0;
            double by = top + plotH - bh;
            g.setColor(BAR);
            g.fill(new Rectangle2D.Double(bx, by, barW, bh));
            g.setColor(AXIS);
            g.setFont(new Font("SansSerif", Font.PLAIN, 11));
            String label = areaStyle && value >= 1000 ? ONE.format(value / 1000.0) + "K" : INT.format(value);
            g.drawString(label, (float) (bx - 2), (float) (by - 4));
        }
        Path2D path = new Path2D.Double();
        boolean started = false;
        g.setStroke(new BasicStroke(2.4f));
        g.setColor(LINE);
        for (int i = 0; i < n; i++)
        {
            Double value = line[i];
            if (value == null) continue;
            double cx = left + slot * i + slot / 2.0;
            double cy = top + plotH - ((value + maxLine) / (2 * maxLine)) * plotH;
            // map YoY to full axis range -max..max visually using 0 at mid if negative present
            double minLine = -maxLine;
            cy = top + plotH - ((value - minLine) / (maxLine - minLine)) * plotH;
            if (!started) { path.moveTo(cx, cy); started = true; }
            else path.lineTo(cx, cy);
            g.fill(new Ellipse2D.Double(cx - 3.5, cy - 3.5, 7, 7));
            g.setFont(new Font("SansSerif", Font.PLAIN, 11));
            g.drawString(ONE.format(value) + "%", (float) (cx + 4), (float) (cy - 6));
        }
        if (started) g.draw(path);
        g.setFont(new Font("SansSerif", Font.PLAIN, 12));
        g.setColor(BAR); g.fillRect(left, height - 28, 12, 12); g.setColor(AXIS); g.drawString(maker, left + 18, height - 17);
        g.setColor(LINE); g.fillRect(left + 110, height - 28, 12, 12); g.setColor(AXIS); g.drawString(maker + " YoY", left + 128, height - 17);
        g.dispose();
        return toPng(image);
    }

    private static byte[] lineChartPng(String title, String maker, List<String> categories, Double[] values, boolean percent) throws IOException
    {
        List<SeriesData> series = List.of(new SeriesData(maker, values, false));
        return multiLinePng(title, categories, series, percent);
    }

    private static byte[] multiLinePng(String title, List<String> categories, List<SeriesData> series, boolean percent) throws IOException
    {
        int width = 900, height = 420;
        BufferedImage image = base(width, height);
        Graphics2D g = image.createGraphics();
        style(g);
        g.setColor(Color.WHITE); g.fillRect(0, 0, width, height);
        drawTitle(g, title, width);
        int left = 70, right = 30, top = 56, bottom = 78;
        int plotW = width - left - right, plotH = height - top - bottom;
        double max = 1;
        for (SeriesData s : series) max = Math.max(max, maxAbs(s.values, 1));
        if (percent) max = Math.max(5, Math.ceil(max / 5.0) * 5.0);
        drawGrid(g, left, top, plotW, plotH, 4);
        drawCategories(g, categories, left, top + plotH, plotW);
        if (percent) drawLeftPercentAxis(g, left, top, plotH, max);
        else drawLeftAxis(g, left, top, plotH, max, false);
        int n = categories.size();
        double slot = plotW / (double) Math.max(1, n);
        for (int s = 0; s < series.size(); s++)
        {
            SeriesData data = series.get(s);
            Color color = SERIES[s % SERIES.length];
            Path2D path = new Path2D.Double();
            boolean started = false;
            g.setStroke(new BasicStroke(2.2f));
            g.setColor(color);
            for (int i = 0; i < n; i++)
            {
                Double value = data.values[i];
                if (value == null) continue;
                double cx = left + slot * i + slot / 2.0;
                double cy = top + plotH - (value / max) * plotH;
                if (!started) { path.moveTo(cx, cy); started = true; }
                else path.lineTo(cx, cy);
                g.fill(new Ellipse2D.Double(cx - 3.2, cy - 3.2, 6.4, 6.4));
                if (percent)
                {
                    g.setFont(new Font("SansSerif", Font.PLAIN, 11));
                    g.drawString(ONE.format(value) + "%", (float) (cx + 4), (float) (cy - 6));
                }
            }
            if (started) g.draw(path);
        }
        int legendX = left;
        int legendY = height - 34;
        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        for (int s = 0; s < series.size(); s++)
        {
            String name = series.get(s).name;
            int nameWidth = Math.min(220, 14 + g.getFontMetrics().stringWidth(name));
            if (legendX + nameWidth > width - 20)
            {
                legendX = left;
                legendY += 16;
            }
            g.setColor(SERIES[s % SERIES.length]);
            g.fillRect(legendX, legendY, 10, 10);
            g.setColor(AXIS);
            g.drawString(name, legendX + 14, legendY + 10);
            legendX += nameWidth + 18;
        }
        g.dispose();
        return toPng(image);
    }

    private static byte[] stackedBarPng(String title, List<String> categories, List<SeriesData> series) throws IOException
    {
        int width = 900, height = 420;
        BufferedImage image = base(width, height);
        Graphics2D g = image.createGraphics();
        style(g);
        g.setColor(Color.WHITE); g.fillRect(0, 0, width, height);
        drawTitle(g, title, width);
        int left = 70, right = 24, top = 56, bottom = 78;
        int plotW = width - left - right, plotH = height - top - bottom;
        int n = categories.size();
        double[] totals = new double[n];
        for (SeriesData s : series)
        {
            for (int i = 0; i < n; i++) if (s.values[i] != null) totals[i] += Math.max(0, s.values[i]);
        }
        double max = 1;
        for (double total : totals) max = Math.max(max, total);
        drawGrid(g, left, top, plotW, plotH, 4);
        drawCategories(g, categories, left, top + plotH, plotW);
        drawLeftAxis(g, left, top, plotH, max, false);
        double slot = plotW / (double) Math.max(1, n);
        double barW = Math.min(52, slot * 0.5);
        double[] stack = new double[n];
        for (int s = 0; s < series.size(); s++)
        {
            SeriesData data = series.get(s);
            g.setColor(SERIES[s % SERIES.length]);
            for (int i = 0; i < n; i++)
            {
                Double value = data.values[i];
                if (value == null || value <= 0) continue;
                double bh = (value / max) * plotH;
                double bx = left + slot * i + (slot - barW) / 2.0;
                double by = top + plotH - ((stack[i] + value) / max) * plotH;
                g.fill(new Rectangle2D.Double(bx, by, barW, bh));
                stack[i] += value;
            }
        }
        int legendX = left;
        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        for (int s = 0; s < series.size(); s++)
        {
            g.setColor(SERIES[s % SERIES.length]); g.fillRect(legendX, height - 30, 10, 10);
            g.setColor(AXIS); g.drawString(series.get(s).name, legendX + 14, height - 20);
            legendX += Math.min(150, 22 + series.get(s).name.length() * 8);
        }
        g.dispose();
        return toPng(image);
    }

    private static byte[] stackedComboPng(String title, List<String> categories, List<SeriesData> series) throws IOException
    {
        List<SeriesData> bars = new ArrayList<>();
        List<SeriesData> lines = new ArrayList<>();
        for (SeriesData s : series) { if (s.secondary) lines.add(s); else bars.add(s); }
        int width = 900, height = 420;
        BufferedImage image = base(width, height);
        Graphics2D g = image.createGraphics();
        style(g);
        g.setColor(Color.WHITE); g.fillRect(0, 0, width, height);
        drawTitle(g, title, width);
        int left = 70, right = 70, top = 56, bottom = 78;
        int plotW = width - left - right, plotH = height - top - bottom;
        int n = categories.size();
        double[] totals = new double[n];
        for (SeriesData s : bars) for (int i = 0; i < n; i++) if (s.values[i] != null) totals[i] += Math.max(0, s.values[i]);
        double maxBar = 1; for (double total : totals) maxBar = Math.max(maxBar, total);
        double maxLine = 10; for (SeriesData s : lines) maxLine = Math.max(maxLine, maxAbs(s.values, 10));
        drawGrid(g, left, top, plotW, plotH, 4);
        drawCategories(g, categories, left, top + plotH, plotW);
        drawLeftAxis(g, left, top, plotH, maxBar, false);
        drawRightAxis(g, left + plotW, top, plotH, maxLine);
        double slot = plotW / (double) Math.max(1, n);
        double barW = Math.min(52, slot * 0.5);
        double[] stack = new double[n];
        for (int s = 0; s < bars.size(); s++)
        {
            SeriesData data = bars.get(s);
            g.setColor(SERIES[s % SERIES.length]);
            for (int i = 0; i < n; i++)
            {
                Double value = data.values[i];
                if (value == null || value <= 0) continue;
                double bh = (value / maxBar) * plotH;
                double bx = left + slot * i + (slot - barW) / 2.0;
                double by = top + plotH - ((stack[i] + value) / maxBar) * plotH;
                g.fill(new Rectangle2D.Double(bx, by, barW, bh));
                stack[i] += value;
            }
        }
        for (int s = 0; s < lines.size(); s++)
        {
            SeriesData data = lines.get(s);
            Color color = SERIES[(bars.size() + s) % SERIES.length];
            Path2D path = new Path2D.Double();
            boolean started = false;
            g.setStroke(new BasicStroke(2.2f));
            g.setColor(color);
            for (int i = 0; i < n; i++)
            {
                Double value = data.values[i];
                if (value == null) continue;
                double cx = left + slot * i + slot / 2.0;
                double cy = top + plotH - ((value + maxLine) / (2 * maxLine)) * plotH;
                double minLine = -maxLine;
                cy = top + plotH - ((value - minLine) / (maxLine - minLine)) * plotH;
                if (!started) { path.moveTo(cx, cy); started = true; }
                else path.lineTo(cx, cy);
                g.fill(new Ellipse2D.Double(cx - 3, cy - 3, 6, 6));
            }
            if (started) g.draw(path);
        }
        int legendX = left;
        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        List<SeriesData> all = new ArrayList<>(); all.addAll(bars); all.addAll(lines);
        for (int s = 0; s < all.size(); s++)
        {
            g.setColor(SERIES[s % SERIES.length]); g.fillRect(legendX, height - 30, 10, 10);
            g.setColor(AXIS); g.drawString(all.get(s).name, legendX + 14, height - 20);
            legendX += Math.min(140, 22 + all.get(s).name.length() * 8);
        }
        g.dispose();
        return toPng(image);
    }

    private static byte[] scatterPng(String title, JSONArray points) throws IOException
    {
        int width = 900, height = 420;
        BufferedImage image = base(width, height);
        Graphics2D g = image.createGraphics();
        style(g);
        g.setColor(Color.WHITE); g.fillRect(0, 0, width, height);
        drawTitle(g, title, width);
        int left = 70, right = 30, top = 56, bottom = 56;
        int plotW = width - left - right, plotH = height - top - bottom;
        double maxSize = 1, maxShip = 1;
        for (Object item : points)
        {
            JSONObject point = (JSONObject) item;
            maxSize = Math.max(maxSize, asDouble(point.get("size")));
            maxShip = Math.max(maxShip, asDouble(point.get("shipment")));
        }
        drawGrid(g, left, top, plotW, plotH, 4);
        g.setColor(AXIS);
        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        g.drawString("Size", left + plotW / 2 - 10, top + plotH + 36);
        for (Object item : points)
        {
            JSONObject point = (JSONObject) item;
            double size = asDouble(point.get("size"));
            double ship = asDouble(point.get("shipment"));
            if (size <= 0 && ship <= 0) continue;
            double cx = left + (size / maxSize) * plotW;
            double cy = top + plotH - (ship / maxShip) * plotH;
            double radius = 8 + 18 * Math.sqrt(ship / maxShip);
            g.setColor(new Color(0xFF, 0xF8, 0xE8));
            g.fill(new Ellipse2D.Double(cx - radius / 2, cy - radius / 2, radius, radius));
            g.setColor(new Color(0xF2, 0xA4, 0x3A));
            g.setStroke(new BasicStroke(2f));
            g.draw(new Ellipse2D.Double(cx - radius / 2, cy - radius / 2, radius, radius));
            g.setColor(AXIS);
            g.setFont(new Font("SansSerif", Font.PLAIN, 10));
            g.drawString(ONE.format(size), (float) (cx - 8), (float) (cy + 3));
        }
        g.dispose();
        return toPng(image);
    }

    private static BufferedImage base(int width, int height)
    {
        return new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
    }

    private static void style(Graphics2D g)
    {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }

    private static void drawTitle(Graphics2D g, String title, int width)
    {
        g.setColor(new Color(0x0D, 0x47, 0x69));
        g.setFont(new Font("SansSerif", Font.BOLD, 18));
        int textW = g.getFontMetrics().stringWidth(title);
        g.drawString(title, Math.max(20, (width - textW) / 2), 34);
    }

    private static void drawGrid(Graphics2D g, int left, int top, int plotW, int plotH, int splits)
    {
        g.setColor(GRID);
        g.setStroke(new BasicStroke(1f));
        for (int i = 0; i <= splits; i++)
        {
            int y = top + (plotH * i / splits);
            g.draw(new Line2D.Double(left, y, left + plotW, y));
        }
        g.setColor(new Color(0xCB, 0xD5, 0xE1));
        g.draw(new Rectangle2D.Double(left, top, plotW, plotH));
    }

    private static void drawCategories(Graphics2D g, List<String> categories, int left, int baseline, int plotW)
    {
        g.setColor(AXIS);
        g.setFont(new Font("SansSerif", Font.PLAIN, 11));
        int n = categories.size();
        double slot = plotW / (double) Math.max(1, n);
        for (int i = 0; i < n; i++)
        {
            String label = categories.get(i);
            int textW = g.getFontMetrics().stringWidth(label);
            g.drawString(label, (float) (left + slot * i + (slot - textW) / 2.0), baseline + 18);
        }
    }

    private static void drawLeftAxis(Graphics2D g, int left, int top, int plotH, double max, boolean areaStyle)
    {
        g.setColor(AXIS);
        g.setFont(new Font("SansSerif", Font.PLAIN, 10));
        for (int i = 0; i <= 4; i++)
        {
            double value = max * (4 - i) / 4.0;
            int y = top + (plotH * i / 4);
            String label = areaStyle && value >= 1000 ? ONE.format(value / 1000.0) + "K" : INT.format(value);
            int textW = g.getFontMetrics().stringWidth(label);
            g.drawString(label, left - textW - 8, y + 4);
        }
    }

    private static void drawLeftPercentAxis(Graphics2D g, int left, int top, int plotH, double max)
    {
        g.setColor(AXIS);
        g.setFont(new Font("SansSerif", Font.PLAIN, 10));
        for (int i = 0; i <= 4; i++)
        {
            double value = max * (4 - i) / 4.0;
            int y = top + (plotH * i / 4);
            String label = ONE.format(value) + "%";
            int textW = g.getFontMetrics().stringWidth(label);
            g.drawString(label, left - textW - 8, y + 4);
        }
    }

    private static void drawRightAxis(Graphics2D g, int x, int top, int plotH, double max)
    {
        g.setColor(AXIS);
        g.setFont(new Font("SansSerif", Font.PLAIN, 10));
        for (int i = 0; i <= 4; i++)
        {
            double value = max - (2 * max * i / 4.0);
            int y = top + (plotH * i / 4);
            String label = ONE.format(value) + "%";
            g.drawString(label, x + 8, y + 4);
        }
    }

    private static double maxAbs(Double[] values, double fallback)
    {
        double max = fallback;
        if (values != null) for (Double value : values) if (value != null) max = Math.max(max, Math.abs(value));
        return max == 0 ? fallback : max;
    }

    private static double asDouble(Object value)
    {
        return value instanceof Number number ? number.doubleValue() : 0;
    }

    private static String safe(String value)
    {
        return value == null || value.isBlank() ? "--" : value;
    }

    private static byte[] toPng(BufferedImage image) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }

    private static final class SeriesData
    {
        private final String name;
        private final Double[] values;
        private final boolean secondary;

        private SeriesData(String name, Double[] values, boolean secondary)
        {
            this.name = name;
            this.values = values;
            this.secondary = secondary;
        }
    }
}
