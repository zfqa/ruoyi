package com.ruoyi.business.knowledge.service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.ruoyi.business.knowledge.domain.KnowledgeChunk;
import com.ruoyi.business.knowledge.domain.KnowledgeFact;

/**
 * 从切片正文抽取指标事实。表格行优先，其次识别「指标名 + 金额」。
 */
public final class KnowledgeFactIndexer
{
    private static final Pattern TABLE_TRIPLE = Pattern.compile(
        "行:\\s*([^|\\n]{2,80}?)\\s*\\|\\s*列:\\s*([^|\\n]{1,40}?)\\s*\\|\\s*值:\\s*([^\\s|]+)");
    private static final Pattern LABEL_NUMBER = Pattern.compile(
        "([\\u4e00-\\u9fff]{2,30})(?:(?!\\d{1,3}(?:,\\d{3}))[^\\n]){0,36}(\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?)");
    private static final Pattern PERCENT = Pattern.compile("(-?\\d+(?:\\.\\d+)?)%");
    private static final Pattern YEAR = Pattern.compile("(20\\d{2})");
    private static final int MAX_PER_CHUNK = 40;

    public List<KnowledgeFact> extract(KnowledgeChunk chunk)
    {
        List<KnowledgeFact> facts = new ArrayList<>();
        if (chunk == null || chunk.getContent() == null || chunk.getContent().isBlank()) return facts;
        String content = chunk.getContent();
        if (content.contains("行:") && content.contains("列:") && content.contains("值:"))
            extractTriples(chunk, content, facts);
        if (facts.isEmpty()) extractLabeledNumbers(chunk, content, facts);
        return facts.size() > MAX_PER_CHUNK ? facts.subList(0, MAX_PER_CHUNK) : facts;
    }

    private void extractTriples(KnowledgeChunk chunk, String content, List<KnowledgeFact> facts)
    {
        Matcher matcher = TABLE_TRIPLE.matcher(content);
        while (matcher.find() && facts.size() < MAX_PER_CHUNK)
        {
            String row = clean(matcher.group(1));
            String col = clean(matcher.group(2));
            String raw = matcher.group(3);
            if (!meaningful(row) || raw == null || raw.isBlank()) continue;
            add(facts, chunk, row, col, row, raw, unitOf(raw, col), yearOf(col + content));
            if (raw.contains("%")) continue;
            String line = lineAt(content, matcher.start());
            attachPercents(facts, chunk, row, line, content);
        }
    }

    private void extractLabeledNumbers(KnowledgeChunk chunk, String content, List<KnowledgeFact> facts)
    {
        Matcher matcher = LABEL_NUMBER.matcher(content);
        while (matcher.find() && facts.size() < MAX_PER_CHUNK)
        {
            String label = clean(matcher.group(1));
            String raw = matcher.group(2);
            if (!meaningful(label)) continue;
            add(facts, chunk, label, "", label, raw, "", yearOf(content.substring(
                Math.max(0, matcher.start() - 30), Math.min(content.length(), matcher.end() + 12))));
            String line = lineAt(content, matcher.start());
            attachPercents(facts, chunk, label, line, content);
        }
    }

    private void attachPercents(List<KnowledgeFact> facts, KnowledgeChunk chunk, String label,
        String line, String content)
    {
        if (line == null || facts.size() >= MAX_PER_CHUNK) return;
        List<String> percents = new ArrayList<>();
        Matcher matcher = PERCENT.matcher(line);
        while (matcher.find()) percents.add(matcher.group(1));
        if (percents.isEmpty()) return;
        boolean ratio = line.contains("占总") || content.contains("占总资产") || line.contains("比重");
        boolean yoy = line.contains("增减") || line.contains("同比") || content.contains("本年比上年增减")
            || content.contains("同比增减");
        if (ratio) add(facts, chunk, label, "占比", label + "占比", percents.get(0), "%", yearOf(content));
        if (yoy) add(facts, chunk, label, "同比", label + "同比", percents.get(percents.size() - 1), "%", yearOf(content));
    }

    private void add(List<KnowledgeFact> facts, KnowledgeChunk chunk, String row, String col,
        String metric, String raw, String unit, Integer year)
    {
        if (facts.size() >= MAX_PER_CHUNK || metric == null || metric.isBlank() || raw == null) return;
        KnowledgeFact fact = new KnowledgeFact();
        fact.setSourceId(chunk.getSourceId());
        fact.setVersionId(chunk.getVersionId());
        fact.setChunkId(chunk.getId());
        fact.setPageStart(chunk.getPageStart());
        fact.setRowLabel(trim(row, 180));
        fact.setColHeader(trim(col, 180));
        fact.setMetricLabel(trim(metric, 180));
        fact.setRawValue(trim(raw.replace("%", ""), 60));
        fact.setUnit(unit == null ? "" : unit);
        fact.setFactYear(year);
        facts.add(fact);
    }

    private static String lineAt(String content, int index)
    {
        int start = content.lastIndexOf('\n', index);
        int end = content.indexOf('\n', index);
        if (start < 0) start = 0;
        else start += 1;
        if (end < 0) end = content.length();
        return content.substring(start, end);
    }

    private static boolean meaningful(String label)
    {
        if (label == null || label.length() < 2) return false;
        String value = label.toLowerCase();
        return !value.startsWith("column") && !value.contains("表头") && !value.equals("标题")
            && !value.equals("数据") && !value.equals("数值");
    }

    private static String unitOf(String raw, String col)
    {
        if (raw != null && raw.contains("%")) return "%";
        if (col != null && (col.contains("人") || col.contains("元"))) return "";
        return "";
    }

    private static Integer yearOf(String text)
    {
        if (text == null) return null;
        Matcher matcher = YEAR.matcher(text);
        Integer year = null;
        while (matcher.find()) year = Integer.parseInt(matcher.group(1));
        return year;
    }

    private static String clean(String value)
    {
        return value == null ? "" : value.replaceAll("^[\\s:：|]+", "").replaceAll("[\\s:：|]+$", "").trim();
    }

    private static String trim(String value, int max)
    {
        if (value == null) return "";
        String text = value.trim();
        return text.length() <= max ? text : text.substring(0, max);
    }
}
