package com.ruoyi.business.knowledge.service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import com.ruoyi.business.knowledge.domain.KnowledgeChunk;

/** 在已投影的报告指标中进行字段化意图匹配，数值结论不依赖LLM自行计算。 */
public final class KnowledgeMetricQueryRouter
{
    private static final Pattern METRIC_QUESTION = Pattern.compile(
        "(?i).*(出货|销量|面积|市占|份额|占比|同比|环比|增长|贡献|排名|多少|数据|shipment|share|yoy|area).*"
    );

    private static final List<TermGroup> GROUPS = List.of(
        group(8, new String[] {"tianma", "天马"}, "tianma", "天马"),
        group(8, new String[] {"auo", "友达"}, "auo", "友达"),
        group(8, new String[] {"boe", "京东方"}, "boe", "京东方"),
        group(8, new String[] {"csot", "chinastar", "华星"}, "csot", "chinastar", "华星"),
        group(6, new String[] {"ltps"}, "ltps"),
        group(6, new String[] {"asi", "a-si", "a si"}, "asi", "a-si"),
        group(5, new String[] {"出货", "shipment", "销量"}, "shipment", "quantity", "出货", "销量"),
        group(5, new String[] {"市占", "市场份额"}, "marketshare", "shipmentshare", "市占", "市场份额"),
        group(5, new String[] {"内部占比"}, "internalshare", "内部占比"),
        group(5, new String[] {"占比", "份额", "share"}, "share", "占比", "份额"),
        group(5, new String[] {"同比", "yoy"}, "yoy", "同比"),
        group(5, new String[] {"面积", "area"}, "displayarea", "area", "面积"),
        group(5, new String[] {"增长贡献", "贡献"}, "growthcontribution", "contribution", "增长贡献"),
        group(4, new String[] {"仪表"}, "仪表", "instrumentcluster"),
        group(4, new String[] {"中控"}, "中控", "centerstackdisplay"),
        group(4, new String[] {"hud", "抬头显示"}, "hud", "headupdisplay"),
        group(4, new String[] {"控制屏"}, "控制屏", "controlpanel"),
        group(4, new String[] {"后视镜"}, "后视镜", "roommirror", "sidemirror"),
        group(4, new String[] {"娱乐屏"}, "娱乐屏", "passengerdisplay"),
        group(5, new String[] {"y25q1-q3", "2025q1-q3", "2025前三季度", "25年前三季度"},
            "y25q1q3", "2025q1q3", "2025前三季度", "25年前三季度"),
        group(3, new String[] {"y25", "2025"}, "y25", "2025"),
        group(3, new String[] {"y24", "2024"}, "y24", "2024"),
        group(3, new String[] {"y23", "2023"}, "y23", "2023"),
        group(3, new String[] {"y22", "2022"}, "y22", "2022"),
        group(3, new String[] {"y26", "2026"}, "y26", "2026")
    );

    /** 整车品牌不在车载面板指标库里。问到这些主体时，指标正文必须出现该品牌。 */
    private static final List<TermGroup> VEHICLE_BRANDS = List.of(
        group(8, new String[] {"byd", "比亚迪"}, "byd", "比亚迪"),
        group(8, new String[] {"xpeng", "小鹏"}, "xpeng", "小鹏"),
        group(8, new String[] {"nio", "蔚来"}, "nio", "蔚来"),
        group(8, new String[] {"li auto", "理想汽车", "理想"}, "li auto", "理想"),
        group(8, new String[] {"tesla", "特斯拉"}, "tesla", "特斯拉"),
        group(8, new String[] {"geely", "吉利"}, "geely", "吉利"),
        group(8, new String[] {"saic", "上汽"}, "saic", "上汽"),
        group(8, new String[] {"gac", "广汽"}, "gac", "广汽"),
        group(8, new String[] {"changan", "长安汽车", "长安"}, "changan", "长安"),
        group(8, new String[] {"great wall", "长城汽车", "长城"}, "great wall", "长城")
    );

    public boolean isMetricQuestion(String question)
    {
        return question != null && METRIC_QUESTION.matcher(question).matches();
    }

    /** 只使用可稳定映射到metric_id/指标JSON的高区分度词，先在MySQL缩小候选集。 */
    public List<String> candidateTerms(String question)
    {
        String query = normalize(question);
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        addCandidate(query, terms, List.of("tianma", "天马"), "tianma");
        addCandidate(query, terms, List.of("auo", "友达"), "auo");
        addCandidate(query, terms, List.of("boe", "京东方"), "boe");
        addCandidate(query, terms, List.of("csot", "chinastar", "华星"), "csot");
        addCandidate(query, terms, List.of("ltps"), "ltps");
        addCandidate(query, terms, List.of("asi", "a-si", "a si"), "asi");
        addCandidate(query, terms, List.of("面积", "area"), "area");
        addCandidate(query, terms, List.of("出货", "shipment", "销量"), "shipment");
        addCandidate(query, terms, List.of("市占", "市场份额"), "share");
        for (TermGroup brand : VEHICLE_BRANDS)
            if (containsAny(query, brand.queryTerms()))
                for (String term : brand.documentTerms()) terms.add(normalize(term));
        addCandidate(query, terms, List.of("fact_pack", "monthly_trend"), "fact_pack");
        for (String application : List.of("仪表", "中控", "hud", "控制屏", "后视镜", "娱乐屏"))
            addCandidate(query, terms, List.of(application), application);
        return new ArrayList<>(terms);
    }

    public List<KnowledgeChunk> rank(String question, List<KnowledgeChunk> candidates, int limit)
    {
        if (!isMetricQuestion(question) || candidates == null || candidates.isEmpty()) return List.of();
        String query = normalize(question);
        List<String> candidateTerms = candidateTerms(question);
        boolean vehicleSales = namesVehicleBrand(query)
            && (query.contains(normalize("销量")) || query.contains(normalize("销售")) || query.contains("sales"));
        List<ScoredChunk> scored = new ArrayList<>();
        for (KnowledgeChunk chunk : candidates)
        {
            String haystack = normalize(String.join(" ", safe(chunk.getMetricId()), safe(chunk.getTitlePath()),
                safe(chunk.getSourceName()), safe(chunk.getOriginalName()), safe(chunk.getContent())));
            if (namesVehicleBrand(query) && !containsAny(haystack, matchedVehicleBrands(query))) continue;
            int score = 0;
            int matchedGroups = 0;
            for (TermGroup group : GROUPS)
            {
                if (!containsAny(query, group.queryTerms())) continue;
                if (containsAny(haystack, group.documentTerms()))
                {
                    score += group.weight();
                    matchedGroups++;
                }
                else score -= Math.max(1, group.weight() / 2);
            }
            String metricId = normalize(chunk.getMetricId());
            for (String term : candidateTerms)
                if (metricId.contains(normalize(term)) || haystack.contains(normalize(term))) score += 4;
            if (metricId.startsWith("dataset")) score -= 12;
            if (vehicleSales)
            {
                if (metricId.contains("marketfactpack") || haystack.contains("monthlytrend")
                    || haystack.contains("销量数值") || (haystack.contains("时间20") && haystack.contains("数值")))
                    score += 20;
                if (metricId.contains("linechart") || metricId.contains("allavailableline"))
                    score += 12;
                if (metricId.equals("marketmeta") || metricId.endsWith("meta"))
                    score -= 18;
            }
            if ((matchedGroups > 0 && score > 0) || (vehicleSales && score > 0))
            {
                chunk.setScore((double) score);
                scored.add(new ScoredChunk(chunk, score, Math.max(matchedGroups, vehicleSales ? 1 : 0)));
            }
        }
        scored.sort(Comparator.comparingInt(ScoredChunk::matchedGroups).reversed()
            .thenComparing(Comparator.comparingInt(ScoredChunk::score).reversed())
            .thenComparing(value -> value.chunk().getId(), Comparator.nullsLast(Comparator.reverseOrder())));
        Set<String> seenMetrics = new LinkedHashSet<>();
        List<KnowledgeChunk> result = new ArrayList<>();
        for (ScoredChunk value : scored)
        {
            KnowledgeChunk chunk = value.chunk();
            String key = safe(chunk.getMetricId());
            if (!key.isBlank() && !seenMetrics.add(key)) continue;
            result.add(chunk);
            if (result.size() >= Math.max(1, limit)) break;
        }
        return result;
    }

    /** 兼容POC早期版本中被按固定长度拆分的指标JSON，按版本和metric_id无损拼回。 */
    public List<KnowledgeChunk> coalesceFragments(List<KnowledgeChunk> candidates)
    {
        if (candidates == null || candidates.isEmpty()) return List.of();
        Map<String, List<KnowledgeChunk>> groups = new LinkedHashMap<>();
        for (KnowledgeChunk chunk : candidates)
        {
            String key = chunk.getVersionId() + ":" + safe(chunk.getMetricId()) + ":" + safe(chunk.getTitlePath());
            groups.computeIfAbsent(key, ignored -> new ArrayList<>()).add(chunk);
        }
        List<KnowledgeChunk> result = new ArrayList<>();
        for (List<KnowledgeChunk> fragments : groups.values())
        {
            fragments.sort(Comparator.comparing(KnowledgeChunk::getChunkNo,
                Comparator.nullsLast(Comparator.naturalOrder())));
            KnowledgeChunk representative = copyOf(fragments.get(0));
            StringBuilder merged = new StringBuilder(safe(representative.getContent()));
            for (int i = 1; i < fragments.size(); i++) appendWithOverlap(merged, safe(fragments.get(i).getContent()));
            representative.setContent(merged.toString());
            representative.setSourceFragments(List.copyOf(fragments));
            result.add(representative);
        }
        return result;
    }

    private KnowledgeChunk copyOf(KnowledgeChunk source)
    {
        KnowledgeChunk copy = new KnowledgeChunk();
        copy.setId(source.getId()); copy.setSourceId(source.getSourceId()); copy.setVersionId(source.getVersionId());
        copy.setChunkNo(source.getChunkNo()); copy.setTitlePath(source.getTitlePath()); copy.setContent(source.getContent());
        copy.setSourceSnippet(source.getSourceSnippet()); copy.setPageStart(source.getPageStart()); copy.setPageEnd(source.getPageEnd());
        copy.setSourceUrl(source.getSourceUrl()); copy.setReportId(source.getReportId()); copy.setMetricId(source.getMetricId());
        copy.setEvidenceJson(source.getEvidenceJson()); copy.setContentSha256(source.getContentSha256()); copy.setTokenCount(source.getTokenCount());
        copy.setSourceName(source.getSourceName()); copy.setOriginalName(source.getOriginalName()); copy.setSourceType(source.getSourceType());
        copy.setVersionNo(source.getVersionNo()); copy.setScore(source.getScore());
        return copy;
    }

    private void appendWithOverlap(StringBuilder target, String next)
    {
        if (next.isBlank()) return;
        int max = Math.min(240, Math.min(target.length(), next.length()));
        int overlap = 0;
        for (int size = max; size > 0; size--)
            if (target.substring(target.length() - size).equals(next.substring(0, size))) { overlap = size; break; }
        target.append(next.substring(overlap));
    }

    private static boolean namesVehicleBrand(String query)
    {
        for (TermGroup brand : VEHICLE_BRANDS)
            if (containsAny(query, brand.queryTerms())) return true;
        return false;
    }

    private static List<String> matchedVehicleBrands(String query)
    {
        List<String> terms = new ArrayList<>();
        for (TermGroup brand : VEHICLE_BRANDS)
            if (containsAny(query, brand.queryTerms())) terms.addAll(brand.documentTerms());
        return terms;
    }

    private static TermGroup group(int weight, String[] queryTerms, String... documentTerms)
    {
        return new TermGroup(weight, List.of(queryTerms), List.of(documentTerms));
    }

    private static boolean containsAny(String value, List<String> terms)
    {
        for (String term : terms) if (value.contains(normalize(term))) return true;
        return false;
    }

    private static void addCandidate(String query, Set<String> target, List<String> aliases, String term)
    {
        if (containsAny(query, aliases)) target.add(normalize(term));
    }

    private static String normalize(String value)
    {
        return safe(value).toLowerCase(Locale.ROOT).replaceAll("[\\s·._—–\\-\"'“”‘’()（）\\[\\]{}]+", "");
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private record TermGroup(int weight, List<String> queryTerms, List<String> documentTerms) { }
    private record ScoredChunk(KnowledgeChunk chunk, int score, int matchedGroups) { }
}
