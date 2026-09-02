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
