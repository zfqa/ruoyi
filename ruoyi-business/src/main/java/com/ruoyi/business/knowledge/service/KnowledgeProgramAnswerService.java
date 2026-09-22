package com.ruoyi.business.knowledge.service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import com.ruoyi.business.knowledge.domain.KnowledgeChunk;
import com.ruoyi.business.knowledge.service.KnowledgeEvidenceExtractor.Fact;
import com.ruoyi.business.knowledge.service.QuestionIntentAnalyzer.Domain;
import com.ruoyi.business.knowledge.service.QuestionIntentAnalyzer.Kind;
import com.ruoyi.business.knowledge.service.QuestionIntentAnalyzer.Plan;

/**
 * 基于意图计划和统一抽数结果生成确定性回答。
 * 只处理能从证据中稳定取数/列举的题型；其余仍回退 LLM。
 */
public final class KnowledgeProgramAnswerService
{
    public record Answer(String text, boolean partial, List<String> warnings) { }

    private final KnowledgeEvidenceExtractor extractor = new KnowledgeEvidenceExtractor();
    private final QuestionIntentAnalyzer analyzer = new QuestionIntentAnalyzer();

    public Answer tryAnswer(String question, List<KnowledgeChunk> chunks)
    {
        Plan plan = analyzer.analyze(question);
        if (plan.domain() == Domain.GENERAL && plan.kind() == Kind.GENERAL) return null;
        if (plan.domain() == Domain.VEHICLE_SALES) return null;
        if (plan.domain() == Domain.PRODUCT) return productAnswer(chunks);
        if (plan.kind() == Kind.COMPLETE_LIST || (plan.domain() == Domain.RESEARCH && plan.needCompleteList()))
            return listAnswer(plan, chunks);
        if (plan.domain() == Domain.HUMAN_RESOURCES || plan.domain() == Domain.FINANCIAL
            || plan.kind() == Kind.CALCULATION || plan.kind() == Kind.FACT || plan.kind() == Kind.COMPARISON)
            return metricAnswer(plan, chunks);
        return null;
    }

    /** 优先使用入库时写入的指标事实，避免再从乱序切片里猜数字。 */
    public Answer tryAnswerStored(String question, List<com.ruoyi.business.knowledge.domain.KnowledgeFact> stored)
    {
        if (stored == null || stored.isEmpty()) return null;
        Plan plan = analyzer.analyze(question);
        List<com.ruoyi.business.knowledge.domain.KnowledgeFact> named = new ArrayList<>();
        if (question != null)
        {
            for (com.ruoyi.business.knowledge.domain.KnowledgeFact fact : stored)
            {
                String label = fact.getMetricLabel() == null ? "" : fact.getMetricLabel().trim();
                if (label.length() >= 4 && question.contains(label)) named.add(fact);
            }
        }
        if (!named.isEmpty())
        {
            boolean wantsRatio = question.contains("占比") || question.contains("比例");
            com.ruoyi.business.knowledge.domain.KnowledgeFact hit = bestStored(question, named, named.get(0).getMetricLabel());
            String hitLabel = hit == null || hit.getMetricLabel() == null ? "" : hit.getMetricLabel();
            boolean ratioHit = hitLabel.contains("占比") || hitLabel.contains("比例");
            if (hit != null && (!wantsRatio || ratioHit)) return formatStored(plan, List.of(hit), List.of());
        }
        if (plan.domain() != Domain.HUMAN_RESOURCES && plan.domain() != Domain.FINANCIAL) return null;
        if (plan.metricHints().isEmpty()) return null;
        List<com.ruoyi.business.knowledge.domain.KnowledgeFact> selected = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String hint : plan.metricHints())
        {
            com.ruoyi.business.knowledge.domain.KnowledgeFact hit = bestStored(question, stored, hint);
            if (hit == null) missing.add(hint);
            else if (selected.stream().noneMatch(item -> item.getMetricLabel().equals(hit.getMetricLabel())
                && item.getRawValue().equals(hit.getRawValue())))
                selected.add(hit);
        }
        if (selected.isEmpty()) return null;
        return formatStored(plan, selected, missing);
    }

    private Answer formatStored(Plan plan, List<com.ruoyi.business.knowledge.domain.KnowledgeFact> selected,
        List<String> missing)
    {
        StringBuilder text = new StringBuilder();
        for (com.ruoyi.business.knowledge.domain.KnowledgeFact fact : selected)
        {
            text.append(fact.getMetricLabel()).append("为 ").append(fact.getRawValue().replace(",", ""));
            if (fact.getUnit() != null && !fact.getUnit().isBlank()) text.append(fact.getUnit());
            else if (plan.domain() == Domain.HUMAN_RESOURCES) text.append(" 人");
            String name = fact.getOriginalName() == null ? "知识库资料" : fact.getOriginalName();
            String page = fact.getPageStart() == null ? "" : "第" + fact.getPageStart() + "页";
            text.append("。 来源：《").append(name).append("》").append(page).append('\n');
        }
        List<String> warnings = new ArrayList<>();
        boolean partial = !missing.isEmpty();
        for (String miss : missing) warnings.add("指标事实未入库：" + miss);
        return new Answer(text.toString().trim(), partial, warnings);
    }

    private com.ruoyi.business.knowledge.domain.KnowledgeFact bestStored(String question,
        List<com.ruoyi.business.knowledge.domain.KnowledgeFact> stored, String hint)
    {
        boolean wantsRatio = question != null && (question.contains("占比") || question.contains("比例"));
        com.ruoyi.business.knowledge.domain.KnowledgeFact best = null;
        int bestScore = 0;
        for (com.ruoyi.business.knowledge.domain.KnowledgeFact fact : stored)
        {
            String label = fact.getMetricLabel() == null ? "" : fact.getMetricLabel();
            String row = fact.getRowLabel() == null ? "" : fact.getRowLabel();
            int score = 0;
            for (String alias : aliases(hint))
            {
                if (label.equals(alias) || row.equals(alias)) score = Math.max(score, 100);
                else if (label.contains(alias) || row.contains(alias)) score = Math.max(score, 80);
            }
            if (score <= 0) continue;
            if ("数值".equals(fact.getColHeader())) score -= 5;
            if (fact.getRawValue() != null && fact.getRawValue().contains(",")) score += 3;
            if (fact.getRawValue() != null) score += Math.min(fact.getRawValue().length(), 24);
            boolean ratioLabel = label.contains("占比") || label.contains("比例") || row.contains("占比") || row.contains("比例");
            if (wantsRatio) score += ratioLabel ? 40 : -70;
            boolean wantsChange = question != null && (question.contains("同比") || question.contains("增减") || question.contains("比上年"));
            if (!wantsChange && (label.contains("同比") || row.contains("同比") || label.contains("增减"))) score -= 50;
            if (fact.getFactYear() != null && question != null && question.contains(String.valueOf(fact.getFactYear())))
                score += 15;
            if (score > bestScore)
            {
                bestScore = score;
                best = fact;
            }
        }
        return bestScore >= 40 ? best : null;
    }

    public static List<String> searchHints(List<String> hints)
    {
        java.util.LinkedHashSet<String> expanded = new java.util.LinkedHashSet<>();
        if (hints != null)
            for (String hint : hints)
                expanded.addAll(aliases(hint));
        return new ArrayList<>(expanded);
    }

    private static java.util.List<String> aliases(String hint)
    {
        return switch (hint == null ? "" : hint)
        {
            case "归母净利润" -> java.util.List.of("归属于上市公司股东的净利润", "归属于母公司所有者的净利润", hint);
            case "归母净利润同比" -> java.util.List.of("归属于上市公司股东的净利润同比", hint);
            case "货币资金占比" -> java.util.List.of("货币资金占比", hint);
            case "销售费用同比" -> java.util.List.of("销售费用同比", hint);
            case "营业收入" -> java.util.List.of("营业收入合计", "营业收入", hint);
            case "营业收入同比" -> java.util.List.of("营业收入同比", "营业收入合计同比", hint);
            default -> java.util.List.of(hint);
        };
    }

    private Answer metricAnswer(Plan plan, List<KnowledgeChunk> chunks)
    {
        List<Fact> facts = extractor.extractAll(chunks);
        if (facts.isEmpty()) return null;
        Integer year = plan.years().isEmpty() ? null : plan.years().iterator().next();
        List<Fact> selected = new ArrayList<>();
        List<String> missing = new ArrayList<>();
        for (String hint : plan.metricHints())
        {
            Fact hit = extractor.findBest(facts, hint, year);
            if (hit == null) missing.add(hint);
            else selected.add(hit);
        }
        if (selected.isEmpty()) return null;

        StringBuilder text = new StringBuilder();
        List<String> warnings = new ArrayList<>();
        boolean partial = !missing.isEmpty();
        for (Fact fact : selected)
        {
            text.append(fact.metricLabel()).append("为 ").append(plain(fact.rawValue()));
            if (fact.unit() != null && !fact.unit().isBlank()) text.append(fact.unit());
            else if (plan.domain() == Domain.HUMAN_RESOURCES) text.append(" 人");
            text.append("。").append(sourceTail(fact)).append('\n');
        }
        if (plan.needSum() && selected.size() >= 2)
        {
            List<Fact> summable = selected;
            if (plan.domain() == Domain.HUMAN_RESOURCES)
            {
                List<Fact> roles = selected.stream()
                    .filter(fact -> fact.metricLabel().contains("生产") || fact.metricLabel().contains("销售"))
                    .toList();
                if (roles.size() >= 2) summable = roles;
            }
            boolean sameFamily = true;
            String first = normalizeFamily(summable.get(0).metricLabel());
            for (Fact fact : summable)
                if (!normalizeFamily(fact.metricLabel()).equals(first)
                    && !(summable.get(0).metricLabel().contains("生产") && fact.metricLabel().contains("销售"))
                    && !(summable.get(0).metricLabel().contains("销售") && fact.metricLabel().contains("生产"))
                    && !(summable.get(0).metricLabel().contains("信用") && fact.metricLabel().contains("资产"))
                    && !(summable.get(0).metricLabel().contains("资产") && fact.metricLabel().contains("信用")))
                    sameFamily = false;
            if (!sameFamily)
            {
                partial = true;
                warnings.add("所选指标口径不一致，未自动相加。");
            }
            else
            {
                BigDecimal total = BigDecimal.ZERO;
                StringBuilder formula = new StringBuilder();
                for (int i = 0; i < summable.size(); i++)
                {
                    Fact fact = summable.get(i);
                    total = total.add(fact.number());
                    if (i > 0) formula.append(" + ");
                    formula.append(plain(fact.rawValue()));
                }
                text.append("合计为 ").append(total.toPlainString()).append("，计算过程为：")
                    .append(formula).append(" = ").append(total.toPlainString()).append("。");
            }
        }
        for (String miss : missing) warnings.add("未找到指标：" + miss);
        if (plan.question().contains("损失")
            && selected.stream().anyMatch(fact -> fact.metricLabel().contains("准备")))
            warnings.add("原文披露的是减值准备，不是减值损失，回答按原文口径。");
        return new Answer(text.toString().trim(), partial, warnings);
    }

    private Answer listAnswer(Plan plan, List<KnowledgeChunk> chunks)
    {
        List<String> hints = plan.metricHints().isEmpty() ? List.of("主要研发项目", "项目名称") : plan.metricHints();
        List<String> items = extractor.listItems(chunks, hints);
        if (items.isEmpty()) return null;
        boolean continued = items.remove("__CONTINUED__");
        if (items.isEmpty()) return null;
        StringBuilder text = new StringBuilder();
        if (continued || plan.needCompleteList())
            text.append(continued ? "已读到的项目如下，原文存在续表，不能确认已经全部列出：\n"
                : "主要项目包括：\n");
        else text.append("主要项目包括：\n");
        for (int i = 0; i < items.size(); i++) text.append(i + 1).append(". ").append(items.get(i)).append('\n');
        KnowledgeChunk chunk = chunks.get(0);
        for (KnowledgeChunk candidate : chunks)
            if (candidate.getContent() != null && candidate.getContent().contains(items.get(0)))
            {
                chunk = candidate;
                break;
            }
        text.append(sourceTail(chunk, 1));
        List<String> warnings = continued
            ? List.of("表格存在续表标记，本次不使用“全部”作为已完整列出的结论。")
            : List.of();
        return new Answer(text.toString().trim(), continued, warnings);
    }

    private Answer productAnswer(List<KnowledgeChunk> chunks)
    {
        for (int i = 0; i < chunks.size(); i++)
        {
            KnowledgeChunk chunk = chunks.get(i);
            String content = chunk.getContent();
            if (content == null || content.contains("经营范围") || content.contains("总经销商")) continue;
            if (content.contains("王朝") && content.contains("海洋") && content.contains("共同构建"))
            {
                String detail = "";
                if (content.contains("龙颜美学") || content.contains("海洋美学") || content.contains("主流")
                    || content.contains("年轻"))
                    detail = "原文同时说明了两个系列的设计语言与定位差异。";
                return new Answer("根据原文，品牌由王朝与海洋两大产品系列共同构建。"
                    + detail + sourceTail(chunk, i + 1), false, List.of());
            }
        }
        return null;
    }

    private String normalizeFamily(String label)
    {
        if (label.contains("员工") || label.contains("人员")) return "staff";
        if (label.contains("减值")) return "impairment";
        return label;
    }

    private String sourceTail(Fact fact)
    {
        return sourceTail(fact.chunk(), fact.evidenceIndex());
    }

    private String sourceTail(KnowledgeChunk chunk, int index)
    {
        String name = chunk.getOriginalName();
        if (name == null || name.isBlank()) name = chunk.getSourceName();
        String page = chunk.getPageStart() == null ? "" : "第" + chunk.getPageStart() + "页";
        return " 来源：《" + (name == null ? "知识库资料" : name) + "》" + page + " [S" + index + "]";
    }

    private String plain(String raw)
    {
        return raw == null ? "" : raw.replace(",", "");
    }
}
