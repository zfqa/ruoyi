package com.ruoyi.business.knowledge.service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/** PDF文本清洗、业务主体识别及检索片段生成。 */
public final class KnowledgeTextProcessor
{
    private static final Pattern NUMERIC_CHART_LINE = Pattern.compile(
        "^[\\s\\d.,%+\\-−–—/\\\\\"'′″()（）]+$");
    private static final Pattern ASI_PATTERN = Pattern.compile("(?i).*a\\s*[-‐‑‒–—]?\\s*si.*");

    private KnowledgeTextProcessor() { }

    public static List<String> detectEntityTerms(String query)
    {
        String value = query == null ? "" : query.toLowerCase(Locale.ROOT);
        List<String> terms = new ArrayList<>();
        addEntity(value, terms, new String[] {"tianma", "天马"}, "Tianma", "天马");
        addEntity(value, terms, new String[] {"auo", "友达"}, "AUO", "友达");
        addEntity(value, terms, new String[] {"boe", "京东方"}, "BOE", "京东方");
        addEntity(value, terms, new String[] {"csot", "china star", "华星"}, "CSOT", "China Star", "华星");
        addEntity(value, terms, new String[] {"xpeng", "小鹏"}, "XPeng", "小鹏");
        addEntity(value, terms, new String[] {"byd", "比亚迪"}, "BYD", "比亚迪");
        addEntity(value, terms, new String[] {"nio", "蔚来"}, "NIO", "蔚来");
        addEntity(value, terms, new String[] {"li auto", "理想汽车", "理想"}, "Li Auto", "理想汽车", "理想");
        addEntity(value, terms, new String[] {"tesla", "特斯拉"}, "Tesla", "特斯拉");
        addEntity(value, terms, new String[] {"geely", "吉利"}, "Geely", "吉利");
        addEntity(value, terms, new String[] {"saic", "上汽"}, "SAIC", "上汽");
        addEntity(value, terms, new String[] {"gac", "广汽"}, "GAC", "广汽");
        addEntity(value, terms, new String[] {"changan", "长安汽车", "长安"}, "Changan", "长安汽车", "长安");
        addEntity(value, terms, new String[] {"great wall", "长城汽车", "长城"}, "Great Wall", "长城汽车", "长城");
        return terms;
    }

    public static String normalizedLiteral(String query)
    {
        if (query == null) return "";
        return ASI_PATTERN.matcher(query).matches() ? "asi" : "";
    }

    public static String cleanPdfText(String text)
    {
        if (text == null || text.isBlank()) return "";
        StringBuilder result = new StringBuilder();
        String previous = null;
        for (String raw : text.replace('\u0000', ' ').split("\\R"))
        {
            String line = raw.replaceAll("[\\t\\x0B\\f\\r]+", " ").replaceAll(" {2,}", " ").trim();
            if (line.isEmpty() || NUMERIC_CHART_LINE.matcher(line).matches()) continue;
            if (line.equals(previous)) continue;
            if (result.length() > 0) result.append('\n');
            result.append(line);
            previous = line;
        }
        return result.toString().replaceAll("\\n{3,}", "\n\n").trim();
    }

    /** 拼回 PDF 视觉折行。只连接行尾没有句读、且下一行仍是同一句的长行。 */
    public static String joinWrappedLines(String text)
    {
        if (text == null || text.isBlank()) return text == null ? "" : text;
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
        StringBuilder out = new StringBuilder();
        String pending = null;
        for (String raw : lines)
        {
            String line = raw.trim();
            if (line.isEmpty())
            {
                pending = flush(out, pending);
                continue;
            }
            if (pending == null)
            {
                pending = line;
                continue;
            }
            if (continuesWrappedLine(pending, line))
                pending = joinPair(pending, line);
            else
            {
                pending = flush(out, pending);
                pending = line;
            }
        }
        flush(out, pending);
        return out.toString();
    }

    private static String flush(StringBuilder out, String pending)
    {
        if (pending == null) return null;
        if (out.length() > 0) out.append('\n');
        out.append(pending);
        return null;
    }

    private static boolean continuesWrappedLine(String current, String next)
    {
        if (current.length() < 18 || next.isEmpty()) return false;
        char end = current.charAt(current.length() - 1);
        if ("。！？；：、.!?;:）)》」】".indexOf(end) >= 0) return false;
        if (next.startsWith("图") || next.startsWith("表") || next.startsWith("数据来源")
            || next.startsWith("资料来源") || next.startsWith("下载日志"))
            return false;
        return !next.matches("^[（(]?\\d+[）).、].*");
    }

    private static String joinPair(String current, String next)
    {
        char end = current.charAt(current.length() - 1);
        char start = next.charAt(0);
        if (isAsciiWord(end) && isAsciiWord(start)) return current + " " + next;
        return current + next;
    }

    private static boolean isAsciiWord(char value)
    {
        return (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z') || (value >= '0' && value <= '9');
    }

    public static String buildSnippet(String content, String query, List<String> entityTerms, int maxLength)
    {
        String cleaned = cleanPdfText(content);
        if (cleaned.isBlank()) return "";
        int match = firstMatch(cleaned, entityTerms);
        if (match < 0 && query != null) match = indexOfIgnoreCase(cleaned, query.trim());
        int start = match < 0 ? 0 : Math.max(0, match - 80);
        int end = Math.min(cleaned.length(), start + Math.max(100, maxLength));
        String snippet = cleaned.substring(start, end).trim();
        if (start > 0) snippet = "…" + snippet;
        if (end < cleaned.length()) snippet += "…";
        return snippet;
    }

    private static void addEntity(String query, List<String> output, String[] aliases, String... searchTerms)
    {
        for (String alias : aliases)
        {
            if (query.contains(alias))
            {
                for (String term : searchTerms) if (!output.contains(term)) output.add(term);
                return;
            }
        }
    }

    private static int firstMatch(String content, List<String> terms)
    {
        int best = -1;
        if (terms == null) return best;
        for (String term : terms)
        {
            int index = indexOfIgnoreCase(content, term);
            if (index >= 0 && (best < 0 || index < best)) best = index;
        }
        return best;
    }

    private static int indexOfIgnoreCase(String value, String term)
    {
        if (term == null || term.isBlank()) return -1;
        return value.toLowerCase(Locale.ROOT).indexOf(term.toLowerCase(Locale.ROOT));
    }
}
