package com.ruoyi.business.knowledge.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;

/** 大模型调度检索时返回的文档和检索词。sourceId 必须来自给出的目录。 */
public final class LlmDispatchPlan
{
    public record Plan(List<Long> sourceIds, List<String> queries, boolean enough)
    {
    }

    private LlmDispatchPlan() { }

    public static Plan parse(String raw, Set<Long> allowed)
    {
        if (raw == null || raw.isBlank() || allowed == null || allowed.isEmpty())
            return new Plan(List.of(), List.of(), true);
        String json = extractJson(raw);
        if (json == null) return new Plan(List.of(), List.of(), true);
        try
        {
            JSONObject root = JSONObject.parseObject(json);
            LinkedHashSet<Long> ids = new LinkedHashSet<>();
            JSONArray idArray = root.getJSONArray("source_ids");
            if (idArray != null)
            {
                for (int i = 0; i < idArray.size(); i++)
                {
                    Long id = idArray.getLong(i);
                    if (id != null && allowed.contains(id)) ids.add(id);
                }
            }
            LinkedHashSet<String> queries = new LinkedHashSet<>();
            JSONArray queryArray = root.getJSONArray("queries");
            if (queryArray != null)
            {
                for (int i = 0; i < queryArray.size() && queries.size() < 6; i++)
                {
                    String query = queryArray.getString(i);
                    if (query == null) continue;
                    query = query.trim();
                    if (query.length() >= 2 && query.length() <= 40) queries.add(query);
                }
            }
            boolean enough = !root.containsKey("enough") || root.getBooleanValue("enough");
            return new Plan(List.copyOf(ids), List.copyOf(queries), enough);
        }
        catch (Exception ignored)
        {
            return new Plan(List.of(), List.of(), true);
        }
    }

    public static String catalogSystemPrompt()
    {
        return "你是知识库调度员。根据文档目录决定要打开哪些资料、用哪些原文用语检索。"
            + "只返回一个 JSON 对象，不要 Markdown，不要解释。"
            + "格式：{\"source_ids\":[数字id],\"queries\":[\"检索词\"],\"enough\":false}。"
            + "source_ids 只能从目录里选。queries 写资料中可能出现的原文说法，2到6个，不要写整句问题。"
            + "问到哪家公司就只打开该公司的资料。";
    }

    public static String sufficiencySystemPrompt()
    {
        return "你负责判断已经打开的原文是否足够回答问题。"
            + "只返回 JSON：证据足够则为 {\"enough\":true,\"source_ids\":[],\"queries\":[]}；"
            + "不够则为 {\"enough\":false,\"source_ids\":[目录中的id],\"queries\":[\"还要查的原文用语\"]}。"
            + "不要解释。";
    }

    private static String extractJson(String raw)
    {
        String text = raw.trim();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) return text.substring(start, end + 1);
        return null;
    }
}
