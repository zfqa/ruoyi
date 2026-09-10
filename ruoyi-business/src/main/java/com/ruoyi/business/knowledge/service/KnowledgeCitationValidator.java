package com.ruoyi.business.knowledge.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import com.ruoyi.business.knowledge.domain.KnowledgeChunk;

/** 对回答逐句校验引用，并把每个结论定位到引用切片中的原文窗口。 */
public class KnowledgeCitationValidator
{
    private static final Pattern CITATION_PATTERN = Pattern.compile("\\[S(\\d+)]");
    private static final Pattern NUMBER_PATTERN = Pattern.compile(
        "(?i)(?<=Y)\\d{2}(?!\\d)|(?<![A-Za-z0-9])[-+]?\\d+(?:[.,]\\d+)*%?");
    private static final Pattern LATIN_TERM_PATTERN = Pattern.compile("[A-Za-z][A-Za-z0-9-]{1,}");
    private static final Pattern CHINESE_PATTERN = Pattern.compile("[\\p{IsHan}]{2,}");
    private static final Pattern BULLET_PATTERN = Pattern.compile("^\\s*(?:[-*+>]|\\d+[.)、]|[一二三四五六七八九十]+[、.])\\s*");
    private static final Set<String> STOP_BIGRAMS = Set.of("根据", "资料", "显示", "指出", "认为", "可以", "以及", "相关", "情况", "其中", "目前");
    private static final Set<String> SECTION_HEADINGS = Set.of("结论", "核心结论", "数据结论", "分析结论",
        "数据与报告证据", "新闻解释", "新闻解释证据", "外部解释", "新闻与政策解释证据",
        "原因分析", "参考来源", "来源");

    public ValidationResult validate(String answer, List<KnowledgeChunk> chunks)
    {
        if (answer == null || answer.isBlank()) throw new IllegalStateException("LLM回答为空");
        if (chunks == null || chunks.isEmpty()) throw new IllegalStateException("没有可用于引用校验的知识切片");

        validateAllCitationNumbers(answer, chunks.size());
        List<String> units = splitClaimUnits(answer);
        List<Map<String, Object>> claims = new ArrayList<>();
        LinkedHashSet<Integer> usedIndexes = new LinkedHashSet<>();
        Map<Integer, List<Map<String, Object>>> evidenceBySource = new LinkedHashMap<>();

        for (String unit : units)
        {
            String claimText = CITATION_PATTERN.matcher(unit).replaceAll("").trim();
            if (!isFactualClaim(claimText)) continue;

            List<Integer> indexes = citationIndexes(unit);
            if (indexes.isEmpty())
                throw new IllegalStateException("LLM回答存在未引用的事实句：" + abbreviate(claimText, 100));

            List<String> labels = new ArrayList<>();
            List<Map<String, Object>> evidences = new ArrayList<>();
            for (Integer index : indexes)
            {
                KnowledgeChunk chunk = chunks.get(index - 1);
                EvidenceLocation location = locateEvidence(claimText, chunk.getContent());
                if (!location.supported)
                    throw new IllegalStateException("事实句引用S" + index + "，但未在该来源原文中找到足够证据："
                        + abbreviate(claimText, 80));

                usedIndexes.add(index);
                String label = "S" + index;
                labels.add(label);
                Map<String, Object> evidence = new LinkedHashMap<>();
                evidence.put("citationLabel", label);
                evidence.put("chunkId", chunk.getId());
                evidence.put("sourceName", chunk.getSourceName());
                evidence.put("originalName", chunk.getOriginalName());
                evidence.put("versionNo", chunk.getVersionNo());
                evidence.put("pageStart", chunk.getPageStart());
                evidence.put("pageEnd", chunk.getPageEnd());
                evidence.put("evidenceSnippet", location.snippet);
                evidence.put("startOffset", location.startOffset);
                evidence.put("endOffset", location.endOffset);
                evidence.put("matchScore", location.score);
                evidence.put("matchedTerms", location.matchedTerms);
                evidences.add(evidence);
                evidenceBySource.computeIfAbsent(index, ignored -> new ArrayList<>()).add(evidence);
            }
            Map<String, Object> claim = new LinkedHashMap<>();
            claim.put("claimText", claimText);
            claim.put("citationLabels", labels);
            claim.put("verified", true);
            claim.put("evidences", evidences);
            claims.add(claim);
        }

        if (claims.isEmpty())
            throw new IllegalStateException("LLM回答没有可校验的带来源事实句");
        return new ValidationResult(new ArrayList<>(usedIndexes), claims, evidenceBySource);
    }

    private void validateAllCitationNumbers(String answer, int sourceCount)
    {
        Matcher matcher = CITATION_PATTERN.matcher(answer);
        boolean found = false;
        while (matcher.find())
        {
            found = true;
            int index = Integer.parseInt(matcher.group(1));
            if (index <= 0 || index > sourceCount)
                throw new IllegalStateException("LLM回答包含无效来源编号S" + index + "，已拒绝返回");
        }
        if (!found) throw new IllegalStateException("LLM回答未给出有效来源编号，已拒绝返回无来源结论");
    }

    private List<String> splitClaimUnits(String answer)
    {
        List<String> result = new ArrayList<>();
        for (String rawLine : answer.replace("\r", "").split("\n"))
        {
            String line = BULLET_PATTERN.matcher(rawLine.trim()).replaceFirst("").trim();
            if (line.isEmpty() || isHeading(line)) continue;
            int start = 0;
            for (int i = 0; i < line.length(); i++)
            {
                if (!isSentenceEnd(line.charAt(i))) continue;
                int end = consumeTrailingCitations(line, i + 1);
                addUnit(result, line.substring(start, end));
                start = end;
                i = Math.max(i, end - 1);
            }
            if (start < line.length()) addUnit(result, line.substring(start));
        }
        return result;
    }

    private int consumeTrailingCitations(String value, int position)
    {
        int cursor = position;
        while (cursor < value.length())
        {
            while (cursor < value.length() && Character.isWhitespace(value.charAt(cursor))) cursor++;
            Matcher matcher = CITATION_PATTERN.matcher(value);
            matcher.region(cursor, value.length());
            if (!matcher.lookingAt()) break;
            cursor = matcher.end();
        }
        return cursor;
    }

    private void addUnit(List<String> units, String value)
    {
        String unit = value.trim();
        if (!unit.isEmpty()) units.add(unit);
    }

    private boolean isSentenceEnd(char value)
    {
        return value == '。' || value == '！' || value == '？' || value == '!' || value == '?' || value == '；' || value == ';';
    }

    private boolean isHeading(String line)
    {
        String plain = CITATION_PATTERN.matcher(line).replaceAll("").trim();
        String headingText = plain.replaceAll("^[#*_`\\s]+|[#*_`\\s]+$", "").trim();
        return plain.startsWith("#") || SECTION_HEADINGS.contains(headingText)
            || (plain.length() <= 40 && (plain.endsWith("：") || plain.endsWith(":")));
    }

    private boolean isFactualClaim(String claim)
    {
        String plain = claim.replaceAll("^[\\s:：—-]+", "").trim();
        if (plain.isEmpty()) return false;
        return !(plain.startsWith("资料不足") || plain.startsWith("现有资料不足")
            || plain.startsWith("无法根据现有资料") || plain.startsWith("无法从现有资料")
            || plain.startsWith("未检索到") || plain.startsWith("未找到匹配")
            || plain.startsWith("当前未检索到") || plain.startsWith("当前没有检索到"));
    }

    private List<Integer> citationIndexes(String unit)
    {
        LinkedHashSet<Integer> indexes = new LinkedHashSet<>();
        Matcher matcher = CITATION_PATTERN.matcher(unit);
        while (matcher.find()) indexes.add(Integer.parseInt(matcher.group(1)));
        return new ArrayList<>(indexes);
    }

    private EvidenceLocation locateEvidence(String claim, String content)
    {
        if (content == null || content.isBlank()) return EvidenceLocation.unsupported();
        List<TextWindow> windows = textWindows(content);
        Set<String> claimTerms = semanticTerms(claim);
        List<NumericToken> claimNumbers = numericTokens(claim);
        EvidenceLocation best = EvidenceLocation.unsupported();
        for (TextWindow window : windows)
        {
            Set<String> evidenceTerms = semanticTerms(window.text);
            Set<String> matched = new LinkedHashSet<>(claimTerms);
            matched.retainAll(evidenceTerms);
            boolean numbersCovered = containsAllNumbers(window.text, claimNumbers);
            double score = claimTerms.isEmpty() ? 0.0 : (double) matched.size() / claimTerms.size();
            String normalizedClaim = normalize(claim);
            boolean exact = normalizedClaim.length() >= 6 && normalize(window.text).contains(normalizedClaim);
            boolean supported = numbersCovered && (exact || score >= 0.18 || matched.size() >= 3);
            if (supported && (!best.supported || score > best.score))
                best = new EvidenceLocation(true, window.text.trim(), window.start, window.end,
                    Math.round(score * 1000.0) / 1000.0, new ArrayList<>(matched));
        }
        return best;
    }

    private List<TextWindow> textWindows(String content)
    {
        List<TextWindow> windows = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < content.length(); i++)
        {
            char value = content.charAt(i);
            if (value == '\n' || isSentenceEnd(value))
            {
                int end = i + 1;
                addWindow(windows, content, start, end);
                start = end;
            }
        }
        addWindow(windows, content, start, content.length());
        if (windows.isEmpty()) addWindow(windows, content, 0, content.length());
        return windows;
    }

    private void addWindow(List<TextWindow> windows, String content, int start, int end)
    {
        while (start < end && Character.isWhitespace(content.charAt(start))) start++;
        while (end > start && Character.isWhitespace(content.charAt(end - 1))) end--;
        if (end <= start) return;
        if (end - start <= 500)
        {
            windows.add(new TextWindow(content.substring(start, end), start, end));
            return;
        }
        for (int cursor = start; cursor < end; cursor += 400)
        {
            int windowEnd = Math.min(end, cursor + 500);
            windows.add(new TextWindow(content.substring(cursor, windowEnd), cursor, windowEnd));
            if (windowEnd == end) break;
        }
    }

    private Set<String> semanticTerms(String value)
    {
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        String searchable = value == null ? "" : value.replaceAll("(?<=[\\p{IsHan}])\\s+(?=[\\p{IsHan}])", "");
        Matcher latin = LATIN_TERM_PATTERN.matcher(searchable);
        while (latin.find()) terms.add(latin.group().toLowerCase(Locale.ROOT));
        if (terms.contains("shipment") || terms.contains("shipments")) terms.add("出货");
        if (terms.contains("yoy"))
        {
            terms.add("同比");
            terms.add("变化");
        }
        if (terms.contains("share")) terms.add("占比");
        if (terms.contains("technology")) terms.add("技术");
        if (terms.contains("application")) terms.add("应用");
        if (terms.contains("size")) terms.add("尺寸");
        Matcher chinese = CHINESE_PATTERN.matcher(searchable);
        while (chinese.find())
        {
            String sequence = chinese.group();
            for (int i = 0; i + 1 < sequence.length(); i++)
            {
                String term = sequence.substring(i, i + 2);
                if (!STOP_BIGRAMS.contains(term)) terms.add(term);
            }
        }
        terms.addAll(values(NUMBER_PATTERN, searchable));
        return terms;
    }

    private Set<String> values(Pattern pattern, String value)
    {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Matcher matcher = pattern.matcher(value == null ? "" : value);
        while (matcher.find()) result.add(normalize(matcher.group()));
        return result;
    }

    private boolean containsAllNumbers(String evidence, Collection<NumericToken> required)
    {
        List<NumericToken> available = numericTokens(evidence);
        for (NumericToken expected : required)
        {
            boolean matched = available.stream().anyMatch(candidate -> numericallyEquivalent(expected, candidate));
            if (!matched) return false;
        }
        return true;
    }

    private List<NumericToken> numericTokens(String value)
    {
        List<NumericToken> result = new ArrayList<>();
        Matcher matcher = NUMBER_PATTERN.matcher(value == null ? "" : value);
        while (matcher.find())
        {
            String raw = matcher.group();
            boolean percent = raw.endsWith("%");
            String number = raw.replace("%", "").replace(",", "");
            try
            {
                double parsed = Double.parseDouble(number);
                int contextStart = Math.max(0, matcher.start() - 8);
                String prefix = value.substring(contextStart, matcher.start()).replaceAll("\\s+", "");
                if (percent && parsed > 0 && prefix.matches(".*(?:下降|减少|下滑|降低|收缩)(?:约|为|了)?$"))
                    parsed = -parsed;
                int dot = number.indexOf('.');
                int decimals = dot < 0 ? 0 : number.length() - dot - 1;
                boolean yearLike = !percent && ((parsed >= 2000 && parsed <= 2099)
                    || (parsed >= 0 && parsed <= 99 && prefix.matches("(?i).*Y$")));
                result.add(new NumericToken(parsed, percent, decimals, yearLike));
            }
            catch (NumberFormatException ignored)
            {
                // Ignore malformed numeric fragments; the semantic matcher still handles surrounding text.
            }
        }
        return result;
    }

    private boolean numericallyEquivalent(NumericToken expected, NumericToken candidate)
    {
        if (expected.yearLike && candidate.yearLike)
        {
            double expectedYear = expected.value < 100 ? expected.value + 2000 : expected.value;
            double candidateYear = candidate.value < 100 ? candidate.value + 2000 : candidate.value;
            return Math.abs(expectedYear - candidateYear) < 1e-9;
        }
        double expectedDisplay = expected.value;
        double candidateDisplay = candidate.value;
        if (expected.percent && !candidate.percent) candidateDisplay *= 100.0;
        else if (!expected.percent && candidate.percent) candidateDisplay /= 100.0;
        double tolerance = Math.max(1e-9, 0.5 * Math.pow(10.0, -expected.decimals));
        return Math.abs(expectedDisplay - candidateDisplay) <= tolerance;
    }

    private String normalize(String value)
    {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\s,，。！？!?；;:：()（）\\[\\]%-]", "");
    }

    private String abbreviate(String value, int max)
    {
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    public static final class ValidationResult
    {
        private final List<Integer> citedIndexes;
        private final List<Map<String, Object>> claims;
        private final Map<Integer, List<Map<String, Object>>> evidenceBySource;

        ValidationResult(List<Integer> citedIndexes, List<Map<String, Object>> claims,
            Map<Integer, List<Map<String, Object>>> evidenceBySource)
        {
            this.citedIndexes = citedIndexes;
            this.claims = claims;
            this.evidenceBySource = evidenceBySource;
        }

        public List<Integer> getCitedIndexes() { return citedIndexes; }
        public List<Map<String, Object>> getClaims() { return claims; }
        public List<Map<String, Object>> getEvidenceForSource(int index)
        {
            return evidenceBySource.getOrDefault(index, List.of());
        }
    }

    private record TextWindow(String text, int start, int end) { }

    private record NumericToken(double value, boolean percent, int decimals, boolean yearLike) { }

    private static final class EvidenceLocation
    {
        private final boolean supported;
        private final String snippet;
        private final int startOffset;
        private final int endOffset;
        private final double score;
        private final List<String> matchedTerms;

        EvidenceLocation(boolean supported, String snippet, int startOffset, int endOffset, double score,
            List<String> matchedTerms)
        {
            this.supported = supported;
            this.snippet = snippet;
            this.startOffset = startOffset;
            this.endOffset = endOffset;
            this.score = score;
            this.matchedTerms = matchedTerms;
        }

        static EvidenceLocation unsupported()
        {
            return new EvidenceLocation(false, "", -1, -1, 0.0, List.of());
        }
    }
}
