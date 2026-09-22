package com.ruoyi.business.knowledge.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 规则意图分析：不调用 LLM，把问题归到少数领域和题型。
 * 后续检索加权、抽数和程序计算都基于此计划，而不是按题面写特判。
 */
public final class QuestionIntentAnalyzer
{
    public enum Domain
    {
        HUMAN_RESOURCES,
        FINANCIAL,
        VEHICLE_SALES,
        PRODUCT,
        RESEARCH,
        GENERAL
    }

    public enum Kind
    {
        FACT,
        CALCULATION,
        COMPLETE_LIST,
        COMPARISON,
        SUMMARY,
        GENERAL
    }

    public record Plan(
        String question,
        Domain domain,
        Kind kind,
        List<String> metricHints,
        Set<Integer> years,
        boolean needSum,
        boolean needCompleteList,
        boolean salesVolume)
    {
    }

    private static final Pattern YEAR = Pattern.compile("(20\\d{2})");
    private static final List<String> PRODUCT_TERMS = List.of(
        "王朝", "海洋", "产品系列", "共同构建", "王朝网", "海洋网");
    private static final List<String> FINANCE_TERMS = List.of(
        "营业收入", "营收", "净利润", "减值", "研发投入", "毛利率", "总资产", "闪电配售", "配售",
        "货币资金", "销售费用", "管理费用", "短期借款", "每股收益", "现金流量");
    private static final List<String> HR_TERMS = List.of(
        "员工", "在职", "母公司", "生产人员", "销售人员", "销售员工", "人数", "专业构成",
        "技术人员", "硕士", "本科", "学历");
    private static final List<String> RESEARCH_TERMS = List.of(
        "研发项目", "主要研发", "项目名称", "刀片电池", "动力电池", "汽车级电池");
    private static final List<String> SUM_TERMS = List.of(
        "合计", "之和", "一共", "总共", "共多少", "分别是多少，合计");
    private static final List<String> LIST_TERMS = List.of(
        "全部", "所有", "列出", "有哪些", "分别是什么");
    private static final List<String> NON_VOLUME = List.of(
        "销售人员", "销售员工", "销售费用", "销售收入", "销售金额",
        "销售渠道", "销售网络", "销售部门", "销售岗位", "销售成本");
    private static final List<String> VOLUME = List.of(
        "销量", "销售量", "销售台数", "销售数量", "多少辆", "万辆", "累计销售", "月度销售", "年度销售");

    public Plan analyze(String question)
    {
        String text = question == null ? "" : question.trim();
        Domain domain = detectDomain(text);
        Kind kind = detectKind(text, domain);
        boolean forbidMix = text.contains("不要混") || text.contains("不要把两个") || text.contains("口径混");
        boolean needSum = containsAny(text, SUM_TERMS) && !forbidMix;
        return new Plan(
            text,
            domain,
            kind,
            metricHints(text, domain),
            yearsIn(text),
            needSum,
            kind == Kind.COMPLETE_LIST || containsAny(text, List.of("全部", "所有")),
            isSalesVolume(text));
    }

    private Domain detectDomain(String text)
    {
        if (isSalesVolume(text)) return Domain.VEHICLE_SALES;
        if (containsAny(text, HR_TERMS)) return Domain.HUMAN_RESOURCES;
        if (containsAny(text, FINANCE_TERMS)) return Domain.FINANCIAL;
        if (containsAny(text, RESEARCH_TERMS)) return Domain.RESEARCH;
        if (containsAny(text, PRODUCT_TERMS)) return Domain.PRODUCT;
        return Domain.GENERAL;
    }

    private Kind detectKind(String text, Domain domain)
    {
        if (containsAny(text, LIST_TERMS) && (domain == Domain.RESEARCH || domain == Domain.PRODUCT
            || text.contains("项目") || text.contains("系列")))
            return text.contains("全部") || text.contains("所有") || text.contains("列出")
                ? Kind.COMPLETE_LIST : Kind.FACT;
        if (containsAny(text, SUM_TERMS) || (text.contains("一共") && text.contains("多少")))
            return Kind.CALCULATION;
        if (text.contains("分别") && (text.contains("多少") || text.contains("是多少") || text.contains("是什么")))
            return Kind.COMPARISON;
        if (text.contains("对比") || text.contains("比较") || text.contains("同比") || text.contains("增减"))
            return Kind.COMPARISON;
        if (text.contains("总结") || text.contains("介绍") || text.contains("如何") || text.contains("概述"))
            return Kind.SUMMARY;
        if (domain == Domain.HUMAN_RESOURCES || domain == Domain.FINANCIAL)
            return Kind.FACT;
        return Kind.GENERAL;
    }

    private List<String> metricHints(String text, Domain domain)
    {
        LinkedHashSet<String> hints = new LinkedHashSet<>();
        if (domain == Domain.HUMAN_RESOURCES)
        {
            boolean staffComposition = text.contains("生产人员") || text.contains("销售人员");
            if (text.contains("母公司")) hints.add("母公司在职员工");
            if (text.contains("子公司") || text.contains("集团")
                || ((text.contains("在职员工") || text.contains("员工数量"))
                    && (text.contains("合计") || text.contains("一共")) && !staffComposition))
                hints.add("在职员工的数量合计");
            if (text.contains("子公司") && !text.contains("集团"))
                hints.add("主要子公司在职员工");
            if (text.contains("生产人员")) hints.add("生产人员");
            if (text.contains("销售人员")) hints.add("销售人员");
            if (text.contains("技术人员")) hints.add("技术人员");
            if (text.contains("硕士")) hints.add("硕士及以上");
            if (hints.isEmpty()) hints.add("在职员工");
        }
        else if (domain == Domain.FINANCIAL)
        {
            if (text.contains("信用减值")) hints.add("信用减值准备");
            if (text.contains("资产减值")) hints.add("资产减值准备");
            if (text.contains("净利润"))
            {
                hints.add("归母净利润");
                if (text.contains("增减") || text.contains("同比") || text.contains("比上年"))
                    hints.add("归母净利润同比");
            }
            if (text.contains("营业收入") || text.contains("营收"))
            {
                hints.add("营业收入");
                if (text.contains("增减") || text.contains("同比") || text.contains("比上年"))
                    hints.add("营业收入同比");
            }
            if (text.contains("货币资金"))
            {
                hints.add("货币资金");
                if (text.contains("比例") || text.contains("占比") || text.contains("总资产"))
                    hints.add("货币资金占比");
            }
            if (text.contains("销售费用"))
            {
                hints.add("销售费用");
                if (text.contains("增减") || text.contains("同比") || text.contains("比上年"))
                    hints.add("销售费用同比");
            }
            if (text.contains("短期借款")) hints.add("短期借款");
            if (text.contains("每股收益")) hints.add("基本每股收益");
            if (text.contains("研发投入"))
            {
                hints.add("研发投入");
                if (text.contains("同比") || text.contains("变化") || text.contains("增减"))
                    hints.add("研发投入同比");
            }
            if (text.contains("汽车金融")) hints.add("比亚迪汽车金融");
            if (text.contains("闪电配售") || text.contains("配售")) hints.add("闪电配售");
            if (text.contains("国家和地区") || text.contains("出海")) hints.add("国家和地区");
        }
        else if (domain == Domain.RESEARCH)
        {
            hints.add("主要研发项目");
            if (text.contains("刀片") || text.contains("电池")) hints.add("刀片电池");
        }
        else if (domain == Domain.PRODUCT)
        {
            hints.add("王朝");
            hints.add("海洋");
            hints.add("共同构建");
        }
        return new ArrayList<>(hints);
    }

    private boolean isSalesVolume(String text)
    {
        String value = text.toLowerCase(Locale.ROOT);
        if (containsAny(value, NON_VOLUME) && !containsAny(value, VOLUME)) return false;
        return containsAny(value, VOLUME);
    }

    private Set<Integer> yearsIn(String text)
    {
        Set<Integer> years = new LinkedHashSet<>();
        Matcher matcher = YEAR.matcher(text == null ? "" : text);
        while (matcher.find()) years.add(Integer.parseInt(matcher.group(1)));
        return years;
    }

    private static boolean containsAny(String value, List<String> terms)
    {
        if (value == null || value.isBlank()) return false;
        for (String term : terms)
            if (value.contains(term)) return true;
        return false;
    }
}
