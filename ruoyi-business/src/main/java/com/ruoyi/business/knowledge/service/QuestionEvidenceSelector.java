package com.ruoyi.business.knowledge.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.ruoyi.business.knowledge.domain.KnowledgeChunk;

/**
 * 用问句里少见的词决定证据文档。
 * 不维护公司词表：一个词只出现在少数文档里，这些文档才留下。
 */
public final class QuestionEvidenceSelector
{
    private static final Pattern TOKEN = Pattern.compile(
        "[\\u4e00-\\u9fff]{2,10}|[A-Za-z][A-Za-z0-9]{1,15}|[\\u4e00-\\u9fff]{1,8}[A-Za-z0-9]{1,6}|[A-Za-z0-9]{1,6}[\\u4e00-\\u9fff]{1,8}");
    private static final Pattern YEAR = Pattern.compile("20\\d{2}");
    private static final Set<String> STOP = Set.of(
        "什么", "哪些", "如何", "怎么", "多少", "大约", "左右", "请问", "介绍", "预测", "分别",
        "是否", "可以", "我们", "他们", "以及", "其中", "目前", "已经", "还是", "不是", "这个",
        "一个", "没有", "关于", "进行", "相关", "情况", "资料", "报告", "研报", "年报", "年度",
        "公司", "汽车", "销量", "出口", "收入", "业务", "主要", "核心", "技术", "定位", "纪录",
        "达成", "上市", "地位", "前景", "最新", "研究");

    private QuestionEvidenceSelector() { }

    public static List<String> contentTerms(String question)
    {
        if (question == null || question.isBlank()) return List.of();
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String segment : question.split("[\\p{P}\\p{S}\\s]+"))
        {
            if (segment == null || segment.isBlank()) continue;
            Matcher matcher = TOKEN.matcher(segment);
            while (matcher.find())
            {
                String term = matcher.group();
                if (useful(term)) terms.add(term);
            }
            addChineseWindows(segment, terms);
        }
        return new ArrayList<>(terms);
    }

    /**
     * 若某些词只出现在一份文档里，丢掉不含任何一个这种词的文档。
     * 所有词都在多份文档里出现时，保持原候选，避免误删。
     */
    public static List<KnowledgeChunk> retainDocumentsWithRarestTerms(String question, List<KnowledgeChunk> chunks)
    {
        if (chunks == null || chunks.size() < 2) return chunks == null ? List.of() : chunks;
        List<String> terms = contentTerms(question);
        if (terms.isEmpty()) return chunks;
        Map<Long, StringBuilder> docs = new LinkedHashMap<>();
        for (KnowledgeChunk chunk : chunks)
        {
            if (chunk == null || chunk.getSourceId() == null) continue;
            StringBuilder text = docs.computeIfAbsent(chunk.getSourceId(), ignored -> new StringBuilder());
            append(text, chunk.getOriginalName());
            append(text, chunk.getSourceName());
            append(text, chunk.getTitlePath());
            append(text, chunk.getContent());
        }
        if (docs.size() < 2) return chunks;
        List<String> rarest = new ArrayList<>();
        int minDf = Integer.MAX_VALUE;
        for (String term : terms)
        {
            int df = 0;
            String needle = term.toLowerCase(Locale.ROOT);
            for (StringBuilder text : docs.values())
                if (text.toString().contains(needle)) df++;
            if (df <= 0) continue;
            if (df < minDf)
            {
                minDf = df;
                rarest.clear();
                rarest.add(term);
            }
            else if (df == minDf)
            {
                rarest.add(term);
            }
        }
        if (minDf != 1 || rarest.isEmpty()) return chunks;
        List<String> decisive = new ArrayList<>();
        for (String term : rarest)
            if (term.length() >= 4) decisive.add(term);
        if (decisive.isEmpty()) return chunks;
        rarest = decisive;
        Set<Long> keep = new LinkedHashSet<>();
        for (Map.Entry<Long, StringBuilder> entry : docs.entrySet())
        {
            String text = entry.getValue().toString();
            for (String term : rarest)
                if (text.contains(term.toLowerCase(Locale.ROOT)))
                {
                    keep.add(entry.getKey());
                    break;
                }
        }
        if (keep.isEmpty() || keep.size() == docs.size()) return chunks;
        List<KnowledgeChunk> filtered = new ArrayList<>();
        for (KnowledgeChunk chunk : chunks)
            if (chunk != null && chunk.getSourceId() != null && keep.contains(chunk.getSourceId()))
                filtered.add(chunk);
        return filtered.isEmpty() ? chunks : filtered;
    }

    private static void addChineseWindows(String segment, Set<String> terms)
    {
        Matcher run = Pattern.compile("[\\u4e00-\\u9fff]+").matcher(segment);
        while (run.find())
        {
            String text = run.group();
            int max = Math.min(6, text.length());
            for (int size = 4; size <= max; size++)
                for (int start = 0; start + size <= text.length(); start++)
                {
                    String window = text.substring(start, start + size);
                    if (useful(window)) terms.add(window);
                }
        }
    }

    private static boolean useful(String term)
    {
        if (term == null) return false;
        term = term.trim();
        if (term.length() < 2 || term.length() > 16) return false;
        if (STOP.contains(term) || YEAR.matcher(term).matches()) return false;
        if (term.contains("多少") || term.contains("大约") || term.contains("什么")) return false;
        return true;
    }

    private static void append(StringBuilder text, String value)
    {
        if (value == null || value.isBlank()) return;
        text.append('\n').append(value.toLowerCase(Locale.ROOT));
    }
}
