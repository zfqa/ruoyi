package com.ruoyi.business.knowledge.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

/**
 * 问题主体解析：规则词表 + LLM 辅助。
 * 不依赖写死某一家车企；新入库主体只要出现在问句里，LLM 即可抽出并约束检索。
 */
public final class QuestionSubjectResolver
{
    public record SubjectPlan(List<String> subjects, boolean mustMatch, String source)
    {
        public boolean hasSubjects()
        {
            return subjects != null && !subjects.isEmpty();
        }
    }

    private QuestionSubjectResolver() { }

    public static SubjectPlan fromRules(String question)
    {
        List<String> subjects = KnowledgeTextProcessor.detectEntityTerms(question);
        return new SubjectPlan(List.copyOf(subjects), !subjects.isEmpty(), "RULE");
    }

    public static SubjectPlan merge(SubjectPlan rules, SubjectPlan llm)
    {
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        if (rules != null && rules.subjects() != null) merged.addAll(rules.subjects());
        if (llm != null && llm.subjects() != null) merged.addAll(llm.subjects());
        boolean mustMatch = (rules != null && rules.mustMatch()) || (llm != null && llm.mustMatch());
        if (merged.isEmpty()) mustMatch = false;
        String source = llm != null && llm.hasSubjects()
            ? (rules != null && rules.hasSubjects() ? "RULE+LLM" : "LLM")
            : (rules != null && rules.hasSubjects() ? "RULE" : "NONE");
        return new SubjectPlan(List.copyOf(merged), mustMatch, source);
    }

    public static SubjectPlan parseLlmJson(String raw)
    {
        if (raw == null || raw.isBlank()) return new SubjectPlan(List.of(), false, "LLM");
        String json = extractJsonObject(raw);
        if (json == null) return new SubjectPlan(List.of(), false, "LLM");
        try
        {
            JSONObject root = JSONObject.parseObject(json);
            LinkedHashSet<String> subjects = new LinkedHashSet<>();
            JSONArray arr = root.getJSONArray("subjects");
            if (arr != null)
            {
                for (int i = 0; i < arr.size(); i++)
                {
                    String term = arr.getString(i);
                    if (term == null) continue;
                    term = term.trim();
                    if (term.length() < 2 || term.length() > 40) continue;
                    if (isNoiseSubject(term)) continue;
                    subjects.add(term);
                }
            }
            Boolean flag = root.getBoolean("must_match");
            boolean mustMatch = flag == null ? !subjects.isEmpty() : flag;
            if (subjects.isEmpty()) mustMatch = false;
            return new SubjectPlan(List.copyOf(subjects), mustMatch, "LLM");
        }
        catch (Exception ignored)
        {
            return new SubjectPlan(List.of(), false, "LLM");
        }
    }

    public static List<Long> parseKeepSourceIds(String raw, Set<Long> allowed)
    {
        if (raw == null || raw.isBlank() || allowed == null || allowed.isEmpty()) return List.of();
        String json = extractJsonObject(raw);
        if (json == null) return List.of();
        try
        {
            JSONObject root = JSONObject.parseObject(json);
            JSONArray arr = root.getJSONArray("keep_source_ids");
            if (arr == null) return List.of();
            LinkedHashSet<Long> keep = new LinkedHashSet<>();
            for (int i = 0; i < arr.size(); i++)
            {
                Long id = arr.getLong(i);
                if (id != null && allowed.contains(id)) keep.add(id);
            }
            return List.copyOf(keep);
        }
        catch (Exception ignored)
        {
            return List.of();
        }
    }

    public static String subjectResolveSystemPrompt()
    {
        return "你是检索前置的主体识别器。从用户问题中抽取回答所依赖的业务主体"
            + "（公司/品牌/机构的中英文名及常见别名），不要抽指标、年份、地区或题型词。"
            + "只返回一个 JSON 对象，不要 Markdown，不要解释。"
            + "格式：{\"subjects\":[\"主体1\",\"别名\"],\"must_match\":true}。"
            + "问题没有明确单一主体（行业整体、泛问）时返回 {\"subjects\":[],\"must_match\":false}。";
    }

    public static String documentTriageSystemPrompt()
    {
        return "你是知识库文档筛选器。根据问题主体，从候选文档中选出真正相关的 sourceId。"
            + "排除明显属于其他公司/品牌的资料（例如问A公司却给B公司年报）。"
            + "只返回 JSON：{\"keep_source_ids\":[数字id,...]}。不要解释。"
            + "若无法判断，返回全部候选 id。";
    }

    public static String documentTriageUserPrompt(String question, List<String> subjects,
        List<DocCandidate> docs)
    {
        StringBuilder sb = new StringBuilder();
        sb.append("问题：").append(question == null ? "" : question).append('\n');
        if (subjects != null && !subjects.isEmpty())
            sb.append("已识别主体：").append(String.join("、", subjects)).append('\n');
        sb.append("候选文档：\n");
        if (docs != null)
        {
            for (DocCandidate doc : docs)
            {
                sb.append("- sourceId=").append(doc.sourceId())
                    .append(" name=").append(doc.name() == null ? "" : doc.name())
                    .append('\n');
            }
        }
        return sb.toString();
    }

    public static boolean mentionsAny(String text, List<String> subjects)
    {
        if (text == null || text.isBlank() || subjects == null || subjects.isEmpty()) return false;
        String lower = text.toLowerCase(Locale.ROOT);
        for (String subject : subjects)
        {
            if (subject == null || subject.isBlank()) continue;
            if (lower.contains(subject.toLowerCase(Locale.ROOT))) return true;
        }
        return false;
    }

    public static List<String> enrichSearchQueries(List<String> baseQueries, List<String> subjects)
    {
        if (subjects == null || subjects.isEmpty())
            return baseQueries == null ? List.of() : new ArrayList<>(baseQueries);
        LinkedHashSet<String> out = new LinkedHashSet<>();
        String primary = subjects.get(0);
        for (String subject : subjects)
        {
            if (subject != null && !subject.isBlank()) out.add(subject.trim());
        }
        if (baseQueries != null)
        {
            int prefixed = 0;
            for (String query : baseQueries)
            {
                if (query == null || query.isBlank()) continue;
                String trimmed = query.trim();
                out.add(trimmed);
                if (!mentionsAny(trimmed, subjects) && prefixed < 4)
                {
                    out.add(primary + " " + trimmed);
                    prefixed++;
                }
            }
        }
        return new ArrayList<>(out);
    }

    public record DocCandidate(Long sourceId, String name) { }

    private static boolean isNoiseSubject(String term)
    {
        String value = term.toLowerCase(Locale.ROOT);
        return value.equals("销量") || value.equals("营收") || value.equals("净利润")
            || value.equals("年报") || value.equals("研报") || value.equals("公司")
            || value.equals("汽车") || value.equals("新能源汽车") || value.equals("数据");
    }

    private static String extractJsonObject(String raw)
    {
        String text = raw.trim();
        if (text.startsWith("```"))
        {
            int start = text.indexOf('{');
            int end = text.lastIndexOf('}');
            if (start >= 0 && end > start) return text.substring(start, end + 1);
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) return text.substring(start, end + 1);
        return null;
    }
}
