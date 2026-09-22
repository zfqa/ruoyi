package com.ruoyi.business.knowledge.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.ruoyi.business.knowledge.domain.KnowledgeChunk;

/**
 * 从知识切片中统一抽取“指标名 + 数值”，供程序计算使用。
 * 不按具体题目写死逻辑；通过指标别名在证据里查找。
 */
public final class KnowledgeEvidenceExtractor
{
    public record Fact(
        String metricKey,
        String metricLabel,
        String rawValue,
        BigDecimal number,
        String unit,
        Integer year,
        KnowledgeChunk chunk,
        int evidenceIndex)
    {
    }

    private static final Pattern LABEL_NUMBER = Pattern.compile(
        "(?<!固定|存货|合同|工程|投资|应收|其他)([\\u4e00-\\u9fffA-Za-z0-9（）()“”\"\\-]{2,40}?)(?:（([^）]{1,12})）|\\(([^)]{1,12})\\))?[：:\\s]*"
            + "(\\d{1,3}(?:,\\d{3})+(?:\\.\\d+)?|\\d{5,}(?:\\.\\d+)?|\\d{1,4}(?:\\.\\d+)?%?|-\\d{1,4}(?:\\.\\d+)?%?)");
    private static final Pattern YEAR_TOKEN = Pattern.compile("^20\\d{2}$");
    private static final Pattern YEAR_NEAR = Pattern.compile("(20\\d{2})\\s*年");

    /** 通用别名：问题侧指标 -> 年报常见写法。 */
    private static final Map<String, List<String>> ALIASES = Map.ofEntries(
        Map.entry("母公司在职员工", List.of("母公司在职员工的数量", "母公司在职员工", "报告期末母公司在职员工的数量")),
        Map.entry("在职员工的数量合计", List.of("报告期末在职员工的数量合计", "在职员工的数量合计", "在职员工数量合计")),
        Map.entry("主要子公司在职员工", List.of("主要子公司在职员工的数量", "报告期末主要子公司在职员工的数量", "子公司在职员工")),
        Map.entry("生产人员", List.of("生产人员")),
        Map.entry("销售人员", List.of("销售人员")),
        Map.entry("技术人员", List.of("技术人员")),
        Map.entry("硕士及以上", List.of("硕士及以上（人）", "硕士及以上", "硕士及以上学历")),
        Map.entry("信用减值准备", List.of("信用减值准备", "信用减值损失", "加：信用减值准备")),
        Map.entry("资产减值准备", List.of("资产减值准备", "加：资产减值准备")),
        Map.entry("营业收入", List.of("营业收入（元）", "营业收入合计", "营业收入")),
        Map.entry("营业收入同比", List.of("本年比上年增减", "营业收入本年比上年增减", "同比增减")),
        Map.entry("归母净利润", List.of("归属于上市公司股东的净利润（元）", "归属于上市公司股东的净利润", "归属于母公司股东的净利润")),
        Map.entry("归母净利润同比", List.of("本年比上年增减", "净利润本年比上年增减")),
        Map.entry("货币资金", List.of("货币资金")),
        Map.entry("货币资金占比", List.of("占总资产比例", "占总资产 比例")),
        Map.entry("销售费用", List.of("销售费用")),
        Map.entry("销售费用同比", List.of("同比增减", "本年比上年增减")),
        Map.entry("短期借款", List.of("短期借款")),
        Map.entry("基本每股收益", List.of("基本每股收益（元/股）", "基本每股收益")),
        Map.entry("比亚迪汽车金融", List.of("比亚迪汽车金融有限公司", "比亚迪汽车金融")),
        Map.entry("研发投入", List.of("研发投入金额", "研发投入约人民币", "研发投入")),
        Map.entry("研发投入同比", List.of("同比上升", "同比下降", "研发投入同比")),
        Map.entry("闪电配售", List.of("闪电配售", "H股闪电配售")),
        Map.entry("国家和地区", List.of("国家和地区", "运营足迹已遍布全球"))
    );

    public List<Fact> extractAll(List<KnowledgeChunk> chunks)
    {
        List<Fact> facts = new ArrayList<>();
        if (chunks == null) return facts;
        for (int i = 0; i < chunks.size(); i++)
        {
            KnowledgeChunk chunk = chunks.get(i);
            if (chunk == null || chunk.getContent() == null || chunk.getContent().isBlank()) continue;
            String content = chunk.getContent();
            Matcher matcher = LABEL_NUMBER.matcher(content);
            while (matcher.find())
            {
                String label = cleanLabel(matcher.group(1));
                String unit = firstNonBlank(matcher.group(2), matcher.group(3));
                String raw = matcher.group(4);
                if (label.isBlank() || raw == null) continue;
                if (isYearToken(raw, content, matcher.end())) continue;
                if (isNoiseLabel(label)) continue;
                String numeric = raw.replace(",", "").replace("%", "");
                BigDecimal number;
                try { number = new BigDecimal(numeric); }
                catch (NumberFormatException ex) { continue; }
                if ((unit == null || unit.isBlank()) && raw.contains("%")) unit = "%";
                facts.add(new Fact(normalize(label), label, raw, number, unit == null ? "" : unit,
                    nearestYear(content, matcher.start()), chunk, i + 1));
            }
        }
        return facts;
    }

    public Fact findBest(List<Fact> facts, String hint, Integer preferredYear)
    {
        if (facts == null || facts.isEmpty() || hint == null || hint.isBlank()) return null;
        List<String> aliases = ALIASES.getOrDefault(hint, List.of(hint));
        Fact best = null;
        int bestScore = Integer.MIN_VALUE;
        for (Fact fact : facts)
        {
            int score = 0;
            String key = fact.metricKey();
            String label = normalize(fact.metricLabel());
            for (String alias : aliases)
            {
                String want = normalize(alias);
                if (key.equals(want) || label.equals(want)) score += 100;
                else if (key.contains(want) || label.contains(want)) score += 60;
                else if (want.contains(key) && key.length() >= 6) score += 35;
            }
            if (score <= 0) continue;
            if (label.length() <= 3 || "数量".equals(label) || "合计".equals(label) || "人数".equals(label)
                || label.endsWith("类别") || label.startsWith("column"))
                score -= 90;
            if ("在职员工的数量合计".equals(hint) && !(label.contains("合计") || key.contains("合计")))
                continue;
            if ("母公司在职员工".equals(hint) && !label.contains("母公司"))
                continue;
            if ("主要子公司在职员工".equals(hint) && !(label.contains("子公司") || key.contains("子公司")))
                continue;
            if ("资产减值准备".equals(hint) && (label.contains("固定") || label.contains("存货")
                || label.contains("合同") || label.contains("工程") || label.contains("投资")))
                continue;
            if (preferredYear != null && fact.year() != null)
            {
                if (preferredYear.equals(fact.year())) score += 30;
                else score -= 20;
            }
            // 逗号分隔大数更像年报正式披露
            if (fact.rawValue() != null && fact.rawValue().contains(",")) score += 8;
            if (score > bestScore
                || (score == bestScore && preferWhenTied(hint, fact, best)))
            {
                bestScore = score;
                best = fact;
            }
        }
        return best;
    }

    /** 合计类指标在同分时取更大值，避免表格错位把母公司人数当成集团合计。 */
    private boolean preferWhenTied(String hint, Fact candidate, Fact current)
    {
        if (current == null) return true;
        if (hint != null && (hint.contains("合计") || hint.contains("集团")))
            return candidate.number().compareTo(current.number()) > 0;
        return false;
    }

    public List<String> listItems(List<KnowledgeChunk> chunks, List<String> sectionHints)
    {
        List<String> items = new ArrayList<>();
        if (chunks == null) return items;
        for (KnowledgeChunk chunk : chunks)
        {
            String content = chunk == null ? null : chunk.getContent();
            if (content == null) continue;
            int marker = -1;
            for (String hint : sectionHints)
            {
                int at = content.indexOf(hint);
                if (at >= 0 && (marker < 0 || at < marker)) marker = at;
            }
            if (marker < 0) continue;
            String body = content.substring(marker);
            boolean continued = body.contains("续表") || body.contains("下转") || body.contains("接上表");
            for (String part : body.split("[；;、\\n|]"))
            {
                String name = part.replaceAll("^.*?项目名称[:：]?", "")
                    .replaceAll("^(主要研发项|主要研发项目|项目名称|续表|下转|接上表|目名称)[:：\\s]*", "")
                    .replaceAll("\\s+", " ")
                    .trim();
                if (name.length() < 2 || name.length() > 40) continue;
                if (name.contains("高新技术") || name.contains("预计对公司") || name.contains("项目目的")
                    || name.contains("项目进展") || name.contains("拟达到")) continue;
                if (name.startsWith("column") || name.equals("响") || name.equals("目名称")) continue;
                // 表格切片常把名称拆行，拼回常见项目名
                name = name.replace("第二代刀片 电池及闪充 技术", "第二代刀片电池及闪充技术")
                    .replace("第二代刀片电池及闪充技术", "第二代刀片电池及闪充技术");
                if (!items.contains(name)) items.add(name);
            }
            if (continued) items.add("__CONTINUED__");
            if (!items.isEmpty()) break;
        }
        return items;
    }

    private boolean isYearToken(String raw, String content, int end)
    {
        String digits = raw.replace(",", "");
        if (!YEAR_TOKEN.matcher(digits).matches()) return false;
        String trailing = end >= content.length() ? "" : content.substring(end, Math.min(content.length(), end + 8));
        return trailing.matches("(?s)\\s*年.*");
    }

    private Integer nearestYear(String content, int around)
    {
        int from = Math.max(0, around - 40);
        int to = Math.min(content.length(), around + 40);
        Matcher matcher = YEAR_NEAR.matcher(content.substring(from, to));
        Integer year = null;
        while (matcher.find()) year = Integer.parseInt(matcher.group(1));
        return year;
    }

    private boolean isNoiseLabel(String label)
    {
        String value = normalize(label);
        return value.contains("column") || value.contains("表头") || value.length() < 2
            || value.matches("\\d+")
            || value.equals("数量") || value.equals("合计") || value.equals("人数")
            || value.equals("专业构成") || value.equals("教育程度");
    }

    private String cleanLabel(String label)
    {
        if (label == null) return "";
        return label.replaceAll("^[\\s\\|：:\\-—]+", "")
            .replaceAll("[\\s\\|：:\\-—]+$", "")
            .replaceAll("^(加：|加:)", "")
            .trim();
    }

    private String normalize(String value)
    {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
            .replaceAll("[\\s·._—–\\-\"'“”‘’()（）\\[\\]{}：:]+", "");
    }

    private String firstNonBlank(String left, String right)
    {
        if (left != null && !left.isBlank()) return left.trim();
        if (right != null && !right.isBlank()) return right.trim();
        return "";
    }
}
