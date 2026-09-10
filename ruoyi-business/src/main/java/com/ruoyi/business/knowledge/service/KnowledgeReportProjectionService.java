package com.ruoyi.business.knowledge.service;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.business.report.domain.AiReport;
import org.springframework.stereotype.Service;

/** 将完整报告JSON投影为精简、可追溯的指标、数据集和结论知识。 */
@Service
public class KnowledgeReportProjectionService
{
    private static final Set<String> NARRATIVE_KEYS = Set.of(
        "executive_summary", "insights", "overview", "drivers", "conclusion", "conclusions", "analysis");
    private static final Set<String> SKIP_NARRATIVE_ROOTS = Set.of(
        "computed_metrics", "evidence", "narrative_sources", "quality");

    public List<ReportKnowledge> project(AiReport report)
    {
        JSONObject root;
        try { root = JSON.parseObject(report.getReportContent()); }
        catch (Exception e) { throw new IllegalArgumentException("结构化报告JSON格式无效", e); }
        if (root == null) throw new IllegalArgumentException("结构化报告JSON为空");

        List<ReportKnowledge> result = new ArrayList<>();
        Map<String, JSONObject> evidenceByMetric = evidenceByMetric(root.getJSONArray("evidence"));
        Map<String, JSONObject> metrics = new LinkedHashMap<>();
        collectMetrics(root.get("computed_metrics"), metrics);
        for (Map.Entry<String, JSONObject> entry : metrics.entrySet())
        {
            String metricId = entry.getKey();
            JSONObject evidence = baseEvidence("METRIC", report);
            evidence.put("metric_id", metricId);
            evidence.put("json_path", findMetricPath(root.get("computed_metrics"), metricId, "$.computed_metrics"));
            JSONObject rawEvidence = evidenceByMetric.get(metricId);
            if (rawEvidence != null) evidence.put("source_evidence", rawEvidence.get("evidence"));
            else
            {
                JSONObject embedded = new JSONObject();
                if (entry.getValue().containsKey("evidence")) embedded.put("evidence", entry.getValue().get("evidence"));
                if (entry.getValue().containsKey("comparison_evidence"))
                    embedded.put("comparison_evidence", entry.getValue().get("comparison_evidence"));
                if (!embedded.isEmpty()) evidence.put("source_evidence", embedded);
            }
            result.add(new ReportKnowledge("指标 / " + metricId, JSON.toJSONString(stripEmbeddedEvidence(entry.getValue())), metricId,
                evidence.toJSONString(), "METRIC"));
        }
        addResidualDatasets(report, root.getJSONObject("computed_metrics"), result);
        addConclusions(report, root, result);
        addReportMetadata(report, root, result);
        if (result.isEmpty()) throw new IllegalArgumentException("结构化报告没有可入库的指标、数据或结论");
        return result;
    }

    private JSONObject baseEvidence(String kind, AiReport report)
    {
        JSONObject value = new JSONObject();
        value.put("kind", kind); value.put("report_id", report.getId());
        return value;
    }

    private Map<String, JSONObject> evidenceByMetric(JSONArray evidence)
    {
        Map<String, JSONObject> result = new LinkedHashMap<>();
        if (evidence == null) return result;
        for (Object value : evidence)
        {
            if (!(value instanceof JSONObject item)) continue;
            String id = item.getString("metric_id");
            if (id != null && !id.isBlank()) result.putIfAbsent(id, item);
        }
        return result;
    }

    private void collectMetrics(Object node, Map<String, JSONObject> metrics)
    {
        if (node instanceof JSONObject object)
        {
            String id = object.getString("metric_id");
            if (id != null && !id.isBlank())
            {
                JSONObject previous = metrics.get(id);
                if (previous == null || object.toJSONString().length() > previous.toJSONString().length()) metrics.put(id, object);
                return;
            }
            for (Object child : object.values()) collectMetrics(child, metrics);
        }
        else if (node instanceof JSONArray array) for (Object child : array) collectMetrics(child, metrics);
    }

    private String findMetricPath(Object node, String id, String path)
    {
        if (node instanceof JSONObject object)
        {
            if (id.equals(object.getString("metric_id"))) return path;
            for (Map.Entry<String, Object> entry : object.entrySet())
            {
                String found = findMetricPath(entry.getValue(), id, path + "." + entry.getKey());
                if (found != null) return found;
            }
        }
        else if (node instanceof JSONArray array)
        {
            for (int i = 0; i < array.size(); i++)
            {
                String found = findMetricPath(array.get(i), id, path + "[" + i + "]");
                if (found != null) return found;
            }
        }
        return null;
    }

    /** 保留computed_metrics中没有metric_id包裹的数据，避免遗漏图表辅助数据。 */
    private void addResidualDatasets(AiReport report, JSONObject metrics, List<ReportKnowledge> target)
    {
        if (metrics == null) return;
        for (Map.Entry<String, Object> section : metrics.entrySet())
        {
            Object residual = withoutMetricObjects(section.getValue());
            if (isEmpty(residual)) continue;
            JSONObject evidence = baseEvidence("DATASET", report);
            evidence.put("json_path", "$.computed_metrics." + section.getKey());
            target.add(new ReportKnowledge("数据集 / " + section.getKey(), JSON.toJSONString(residual),
                "dataset." + section.getKey(), evidence.toJSONString(), "DATASET"));
        }
    }

    private Object withoutMetricObjects(Object node)
    {
        if (node instanceof JSONObject object)
        {
            if (object.getString("metric_id") != null) return null;
            JSONObject copy = new JSONObject();
            for (Map.Entry<String, Object> entry : object.entrySet())
            {
                Object value = withoutMetricObjects(entry.getValue());
                if (!isEmpty(value)) copy.put(entry.getKey(), value);
            }
            return copy;
        }
        if (node instanceof JSONArray array)
        {
            JSONArray copy = new JSONArray();
            for (Object child : array)
            {
                Object value = withoutMetricObjects(child);
                if (!isEmpty(value)) copy.add(value);
            }
            return copy;
        }
        return node;
    }

    /** 指标正文只保留计算结果；可能很大的行级证据由evidence_json独立承载。 */
    private Object stripEmbeddedEvidence(Object node)
    {
        if (node instanceof JSONObject object)
        {
            JSONObject copy = new JSONObject();
            for (Map.Entry<String, Object> entry : object.entrySet())
                if (!Set.of("evidence", "comparison_evidence", "source_evidence").contains(entry.getKey()))
                    copy.put(entry.getKey(), stripEmbeddedEvidence(entry.getValue()));
            return copy;
        }
        if (node instanceof JSONArray array)
        {
            JSONArray copy = new JSONArray();
            for (Object child : array) copy.add(stripEmbeddedEvidence(child));
            return copy;
        }
        return node;
    }

    private boolean isEmpty(Object value)
    {
        return value == null || value instanceof JSONObject object && object.isEmpty()
            || value instanceof JSONArray array && array.isEmpty();
    }

    private void addConclusions(AiReport report, JSONObject root, List<ReportKnowledge> target)
    {
        Map<String, JSONObject> traced = new LinkedHashMap<>();
        JSONArray sources = root.getJSONArray("narrative_sources");
        if (sources != null) for (Object value : sources)
        {
            if (!(value instanceof JSONObject item)) continue;
            String text = item.getString("conclusion");
            if (text != null && !text.isBlank()) traced.putIfAbsent(normalize(text), item);
        }

        LinkedHashSet<String> conclusions = new LinkedHashSet<>();
        collectNarratives(root, null, false, conclusions);
        conclusions.addAll(traced.keySet());
        int number = 1;
        for (String normalized : conclusions)
        {
            JSONObject source = traced.get(normalized);
            String text = source == null ? normalized : source.getString("conclusion").trim();
            JSONObject evidence = baseEvidence("CONCLUSION", report);
            evidence.put("conclusion_no", number);
            if (source == null) evidence.put("traceability", "REPORT_ONLY");
            else
            {
                evidence.put("traceability", "METRIC_EVIDENCE");
                evidence.put("citation_label", source.getString("citation_label"));
                evidence.put("source_file", source.getString("source_file"));
                evidence.put("metric_ids", source.get("metric_ids")); evidence.put("metrics", source.get("metrics"));
                if (source.containsKey("evidence")) evidence.put("source_evidence", source.get("evidence"));
            }
            target.add(new ReportKnowledge("结论 / " + number, "结论：" + text, firstMetricId(source),
                evidence.toJSONString(), "CONCLUSION"));
            number++;
        }
    }

    private void collectNarratives(Object node, String key, boolean insideNarrative, Collection<String> target)
    {
        boolean narrative = insideNarrative || key != null && NARRATIVE_KEYS.contains(key);
        if (node instanceof JSONObject object)
        {
            for (Map.Entry<String, Object> entry : object.entrySet())
            {
                if (!SKIP_NARRATIVE_ROOTS.contains(entry.getKey()))
                    collectNarratives(entry.getValue(), entry.getKey(), narrative, target);
            }
        }
        else if (node instanceof JSONArray array)
            for (Object child : array) collectNarratives(child, key, narrative, target);
        else if (node instanceof String text && narrative)
        {
            String normalized = normalize(text);
            if (!normalized.isBlank()) target.add(normalized);
        }
    }

    private String normalize(String value) { return value == null ? "" : value.replaceAll("\\s+", " ").trim(); }

    private String firstMetricId(JSONObject source)
    {
        if (source == null) return "";
        JSONArray ids = source.getJSONArray("metric_ids");
        return ids == null || ids.isEmpty() ? "" : String.valueOf(ids.get(0));
    }

    private void addReportMetadata(AiReport report, JSONObject root, List<ReportKnowledge> target)
    {
        JSONObject content = new JSONObject();
        content.put("report_id", report.getId()); content.put("report_type", report.getReportType());
        content.put("generation_mode", report.getGenerationMode());
        if (root.containsKey("quality")) content.put("quality", root.get("quality"));
        if (root.containsKey("data_gaps")) content.put("data_gaps", root.get("data_gaps"));
        if (content.size() <= 3) return;
        target.add(new ReportKnowledge("报告元数据", content.toJSONString(), "",
            baseEvidence("REPORT_METADATA", report).toJSONString(), "METADATA"));
    }

    public record ReportKnowledge(String title, String content, String metricId, String evidenceJson, String kind) {}
}
