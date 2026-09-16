package com.ruoyi.business.analysis.vehicle.controller;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import jakarta.servlet.http.HttpServletRequest;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.business.analysis.vehicle.service.MarketAgentGateway;
import com.ruoyi.business.analysis.vehicle.service.MarketAgentGateway.AgentResponse;
import com.ruoyi.business.analysis.vehicle.service.MarketAgentGateway.BinaryResponse;
import com.ruoyi.business.analysis.vehicle.domain.VehicleAnalysis;
import com.ruoyi.business.analysis.vehicle.service.IVehicleAnalysisService;
import com.ruoyi.business.knowledge.domain.KnowledgeIngestTask;
import com.ruoyi.business.knowledge.service.KnowledgeIngestService;
import com.ruoyi.common.core.controller.BaseController;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 整车市场洞察网关：统一承接若依鉴权，再转发到本机 Python 分析引擎。
 */
@RestController
@RequestMapping("/business/market")
public class MarketAgentController extends BaseController
{
    private static final String PARSER_VERSION = "21.0";
    private static final Logger log = LoggerFactory.getLogger(MarketAgentController.class);

    private final MarketAgentGateway gateway;
    private final IVehicleAnalysisService vehicleAnalysisService;
    private final KnowledgeIngestService knowledgeIngestService;
    /**
     * Excel 导入页的临时解析任务只用于返回解析预览，不能升级为整车分析数据集。
     * 所有权只保存到任务结束，页面刷新也不会恢复该任务。
     */
    private final ConcurrentMap<String, String> excelParseJobOwners = new ConcurrentHashMap<>();

    @Autowired
    public MarketAgentController(MarketAgentGateway gateway, IVehicleAnalysisService vehicleAnalysisService,
        KnowledgeIngestService knowledgeIngestService)
    {
        this.gateway = gateway;
        this.vehicleAnalysisService = vehicleAnalysisService;
        this.knowledgeIngestService = knowledgeIngestService;
    }

    /** Kept for focused gateway integration tests that do not bootstrap the knowledge subsystem. */
    public MarketAgentController(MarketAgentGateway gateway, IVehicleAnalysisService vehicleAnalysisService)
    {
        this(gateway, vehicleAnalysisService, null);
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:vehicle:list')")
    @GetMapping("/health")
    public ResponseEntity<String> health() { return get("/health", null); }

    @PreAuthorize("@ss.hasPermi('business:analysis:vehicle:list')")
    @GetMapping("/llm/status")
    public ResponseEntity<String> llmStatus() { return get("/llm/status", null); }

    @PreAuthorize("@ss.hasPermi('business:analysis:vehicle:query')")
    @PostMapping("/llm/test")
    public ResponseEntity<String> testLlm() { return post("/llm/test", null, "{}"); }

    /**
     * Excel 导入模块的解析入口。
     *
     * 与 /upload/jobs 不同：此入口不会创建 business_analysis_vehicle 记录，
     * 不会把 dataset_id 暴露为可分析任务，也不会建立 Excel 导入到市场分析的跳转关系。
     */
    @PreAuthorize("@ss.hasPermi('business:data:excel:add')")
    @PostMapping(value = "/parse/jobs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> createExcelParseJob(@RequestParam("files") MultipartFile[] files)
    {
        if (files == null || files.length == 0) return gatewayError("至少选择一个Excel/CSV文件", 400);
        try
        {
            AgentResponse response = gateway.postMultipart("/upload/jobs", "files", files);
            if (response.getStatusCode() >= 200 && response.getStatusCode() < 300)
            {
                String jobId = JSON.parseObject(response.getBody()).getString("job_id");
                if (jobId != null && !jobId.isBlank()) excelParseJobOwners.put(jobId, getUsername());
            }
            return json(response);
        }
        catch (Exception ex)
        {
            log.error("创建 Excel 临时解析任务失败", ex);
            return unavailable(ex);
        }
    }

    @PreAuthorize("@ss.hasPermi('business:data:excel:list')")
    @GetMapping("/parse/jobs/{jobId}")
    public ResponseEntity<String> excelParseJob(@PathVariable String jobId)
    {
        if (!canAccessExcelParseJob(jobId)) return parseJobForbidden();
        try
        {
            AgentResponse response = gateway.get("/upload/jobs/" + MarketAgentGateway.encodePath(jobId), null);
            if (response.getStatusCode() >= 200 && response.getStatusCode() < 300)
            {
                JSONObject body = JSON.parseObject(response.getBody());
                String status = body.getString("status");
                if ("success".equals(status))
                {
                    JSONObject result = body.getJSONObject("result");
                    if (result != null) result.remove("dataset_id");
                    excelParseJobOwners.remove(jobId);
                    return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body.toJSONString());
                }
            }
            return json(response);
        }
        catch (Exception ex) { return unavailable(ex); }
    }

    @PreAuthorize("@ss.hasPermi('business:data:excel:edit')")
    @PostMapping("/parse/jobs/{jobId}/cancel")
    public ResponseEntity<String> cancelExcelParseJob(@PathVariable String jobId)
    {
        if (!canAccessExcelParseJob(jobId)) return parseJobForbidden();
        return post("/upload/jobs/" + MarketAgentGateway.encodePath(jobId) + "/cancel", null, "{}");
    }

    @PreAuthorize("@ss.hasPermi('business:data:excel:add')")
    @PostMapping("/parse/jobs/{jobId}/retry")
    public ResponseEntity<String> retryExcelParseJob(@PathVariable String jobId)
    {
        if (!canAccessExcelParseJob(jobId)) return parseJobForbidden();
        try
        {
            AgentResponse response = gateway.postJson("/upload/jobs/" + MarketAgentGateway.encodePath(jobId) + "/retry", null, "{}");
            if (response.getStatusCode() >= 200 && response.getStatusCode() < 300)
            {
                String newJobId = JSON.parseObject(response.getBody()).getString("job_id");
                if (newJobId != null && !newJobId.isBlank()) excelParseJobOwners.put(newJobId, getUsername());
            }
            return json(response);
        }
        catch (Exception ex) { return unavailable(ex); }
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:vehicle:add')")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> upload(@RequestParam("files") MultipartFile[] files)
    {
        if (files == null || files.length == 0)
        {
            return gatewayError("至少选择一个市场数据文件", 400);
        }
        try
        {
            AgentResponse response = gateway.postMultipart("/upload/batch", "files", files);
            if (response.getStatusCode() >= 200 && response.getStatusCode() < 300)
            {
                saveDatasetMetadata(response.getBody());
            }
            return json(response);
        }
        catch (Exception ex)
        {
            return unavailable(ex);
        }
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:vehicle:add')")
    @PostMapping(value = "/upload/jobs", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> createUploadJob(@RequestParam("files") MultipartFile[] files)
    {
        if (files == null || files.length == 0) return gatewayError("至少选择一个市场数据文件", 400);
        try
        {
            AgentResponse response = gateway.postMultipart("/upload/jobs", "files", files);
            if (response.getStatusCode() >= 200 && response.getStatusCode() < 300)
            {
                JSONObject body = JSON.parseObject(response.getBody());
                VehicleAnalysis task = new VehicleAnalysis();
                task.setDatasetId("job:" + body.getString("job_id"));
                task.setTaskName(String.join("、", body.getList("file_names", String.class)));
                task.setFileName(task.getTaskName());
                task.setSourceFilesJson(JSON.toJSONString(body.getJSONArray("file_names")));
                task.setStatus("1");
                task.setParserVersion(PARSER_VERSION);
                task.setCreateBy(getUsername());
                task.setRemark("Python解析任务处理中");
                vehicleAnalysisService.insertVehicleAnalysis(task);
            }
            return json(response);
        }
        catch (Exception ex)
        {
            log.error("创建市场分析上传任务失败", ex);
            return unavailable(ex);
        }
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:vehicle:list')")
    @GetMapping("/upload/jobs/{jobId}")
    public ResponseEntity<String> uploadJob(@PathVariable String jobId)
    {
        String jobDatasetId = "job:" + jobId;
        try
        {
            VehicleAnalysis task = vehicleAnalysisService.selectVehicleAnalysisByDatasetId(jobDatasetId);
            if (task != null && !canAccessDataset(jobDatasetId)) return datasetForbidden();
            AgentResponse response = gateway.get("/upload/jobs/" + MarketAgentGateway.encodePath(jobId), null);
            if (response.getStatusCode() >= 200 && response.getStatusCode() < 300)
            {
                JSONObject body = JSON.parseObject(response.getBody());
                String status = body.getString("status");
                // 成功响应返回数据库后，如果浏览器恰好丢失响应并刷新，job:ID 已被
                // 正式 dataset_id 替换。此时通过结果里的 dataset_id 再做一次所有权校验，
                // 允许原用户恢复成功结果，但不会向其他用户泄露任务内容。
                if (task == null)
                {
                    JSONObject result = body.getJSONObject("result");
                    String completedDatasetId = result == null ? null : result.getString("dataset_id");
                    if (!"success".equals(status) || !canAccessDataset(completedDatasetId)) return datasetForbidden();
                    return json(response);
                }
                if (task != null && "success".equals(status) && body.getJSONObject("result") != null)
                {
                    JSONObject result = body.getJSONObject("result");
                    String completedDatasetId = result.getString("dataset_id");
                    VehicleAnalysis completed = vehicleAnalysisService.selectVehicleAnalysisByDatasetId(completedDatasetId);

                    // 同一文件经过确定性解析后会得到相同的 dataset_id。此前再次上传
                    // 同一文件时，临时 job:ID 记录在这里改写为已有 dataset_id，触发
                    // 唯一键冲突，浏览器只能看到笼统的 Internal Server Error。复用已有
                    // 数据集记录即可：数据文件内容相同，报告配置和问答资料也应继续保留。
                    if (completed != null && !completed.getId().equals(task.getId()))
                    {
                        if (!canAccessDataset(completedDatasetId))
                        {
                            task.setStatus("3");
                            task.setRemark("该数据集已由其他用户创建，无权复用");
                            task.setUpdateBy(getUsername());
                            vehicleAnalysisService.updateVehicleAnalysis(task);
                            return datasetForbidden();
                        }
                        fillDatasetMetadata(completed, result);
                        completed.setStatus("2");
                        completed.setRemark("重复上传相同文件，复用确定性解析结果");
                        completed.setUpdateBy(getUsername());
                        vehicleAnalysisService.updateVehicleAnalysis(completed);
                        vehicleAnalysisService.deleteVehicleAnalysisById(task.getId());
                        return json(response);
                    }

                    fillDatasetMetadata(task, result);
                    task.setStatus("2");
                    task.setRemark("Python确定性解析完成");
                    task.setUpdateBy(getUsername());
                    vehicleAnalysisService.updateVehicleAnalysis(task);
                }
                else if (task != null && ("failed".equals(status) || "cancelled".equals(status)))
                {
                    task.setStatus("failed".equals(status) ? "3" : "0");
                    task.setRemark(body.getString("message"));
                    task.setUpdateBy(getUsername());
                    vehicleAnalysisService.updateVehicleAnalysis(task);
                }
            }
            else if (task == null) return datasetForbidden();
            return json(response);
        }
        catch (Exception ex)
        {
            log.error("读取市场分析上传任务失败，jobId={}", jobId, ex);
            return unavailable(ex);
        }
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:vehicle:edit')")
    @PostMapping("/upload/jobs/{jobId}/cancel")
    public ResponseEntity<String> cancelUploadJob(@PathVariable String jobId)
    {
        return postDataset("job:" + jobId, "/upload/jobs/" + MarketAgentGateway.encodePath(jobId) + "/cancel", null, "{}");
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:vehicle:add')")
    @PostMapping("/upload/jobs/{jobId}/retry")
    public ResponseEntity<String> retryUploadJob(@PathVariable String jobId)
    {
        String oldKey = "job:" + jobId;
        if (!canAccessDataset(oldKey)) return datasetForbidden();
        try
        {
            AgentResponse response = gateway.postJson("/upload/jobs/" + MarketAgentGateway.encodePath(jobId) + "/retry", null, "{}");
            if (response.getStatusCode() >= 200 && response.getStatusCode() < 300)
            {
                JSONObject body = JSON.parseObject(response.getBody());
                VehicleAnalysis old = vehicleAnalysisService.selectVehicleAnalysisByDatasetId(oldKey);
                VehicleAnalysis task = new VehicleAnalysis();
                task.setDatasetId("job:" + body.getString("job_id"));
                task.setTaskName(old.getTaskName()); task.setFileName(old.getFileName());
                task.setSourceFilesJson(old.getSourceFilesJson()); task.setStatus("1");
                task.setParserVersion(PARSER_VERSION); task.setCreateBy(getUsername()); task.setRemark("重试解析处理中");
                vehicleAnalysisService.insertVehicleAnalysis(task);
            }
            return json(response);
        }
        catch (Exception ex) { return unavailable(ex); }
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:vehicle:list')")
    @GetMapping("/sheets/{datasetId}")
    public ResponseEntity<String> sheets(@PathVariable String datasetId)
    {
        return getDataset(datasetId, "/sheets/" + MarketAgentGateway.encodePath(datasetId), null);
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:vehicle:list')")
    @GetMapping("/period-options/{datasetId}")
    public ResponseEntity<String> periodOptions(@PathVariable String datasetId, HttpServletRequest request)
    {
        return getDataset(datasetId, "/period-options/" + MarketAgentGateway.encodePath(datasetId), request.getQueryString());
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:vehicle:list')")
    @GetMapping("/analysis/{datasetId}")
    public ResponseEntity<String> analysis(@PathVariable String datasetId, HttpServletRequest request)
    {
        return getDataset(datasetId, "/analysis/" + MarketAgentGateway.encodePath(datasetId), request.getQueryString());
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:vehicle:list')")
    @GetMapping("/dashboard-components/{datasetId}")
    public ResponseEntity<String> components(@PathVariable String datasetId, HttpServletRequest request)
    {
        return getDataset(datasetId, "/dashboard-components/" + MarketAgentGateway.encodePath(datasetId), request.getQueryString());
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:vehicle:list')")
    @GetMapping("/context/{datasetId}")
    public ResponseEntity<String> context(@PathVariable String datasetId)
    {
        return getDataset(datasetId, "/context/" + MarketAgentGateway.encodePath(datasetId), null);
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:vehicle:edit')")
    @PostMapping("/context/{datasetId}/text")
    public ResponseEntity<String> addContextText(@PathVariable String datasetId, @RequestBody Map<String, Object> body)
    {
        return postDataset(datasetId, "/context/" + MarketAgentGateway.encodePath(datasetId) + "/text", null, JSON.toJSONString(body));
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:vehicle:edit')")
    @PostMapping(value = "/context/{datasetId}/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> addContextFiles(@PathVariable String datasetId,
        @RequestParam("files") MultipartFile[] files)
    {
        if (!canAccessDataset(datasetId)) return datasetForbidden();
        try
        {
            return json(gateway.postMultipart("/context/" + MarketAgentGateway.encodePath(datasetId) + "/upload", "files", files));
        }
        catch (Exception ex)
        {
            return unavailable(ex);
        }
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:vehicle:edit')")
    @DeleteMapping("/context/{datasetId}")
    public ResponseEntity<String> deleteContext(@PathVariable String datasetId)
    {
        if (!canAccessDataset(datasetId)) return datasetForbidden();
        try
        {
            return json(gateway.delete("/context/" + MarketAgentGateway.encodePath(datasetId)));
        }
        catch (Exception ex)
        {
            return unavailable(ex);
        }
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:vehicle:edit')")
    @PostMapping("/context/{datasetId}/delete-items")
    public ResponseEntity<String> deleteContextItems(@PathVariable String datasetId,
        @RequestBody Map<String, Object> body)
    {
        return postDataset(datasetId, "/context/" + MarketAgentGateway.encodePath(datasetId) + "/delete-items",
            null, JSON.toJSONString(body));
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:vehicle:edit')")
    @PutMapping("/context/{datasetId}/{itemId}/category")
    public ResponseEntity<String> updateContextCategory(@PathVariable String datasetId,
        @PathVariable String itemId, @RequestBody Map<String, Object> body)
    {
        if (!canAccessDataset(datasetId)) return datasetForbidden();
        try
        {
            return json(gateway.putJson("/context/" + MarketAgentGateway.encodePath(datasetId) + "/"
                + MarketAgentGateway.encodePath(itemId) + "/category", null, JSON.toJSONString(body)));
        }
        catch (Exception ex)
        {
            return unavailable(ex);
        }
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:vehicle:query')")
    @PostMapping("/chat")
    public ResponseEntity<String> chat(@RequestBody Map<String, Object> body)
    {
        Object value = body.get("dataset_id");
        if (value == null || !canAccessDataset(String.valueOf(value))) return datasetForbidden();
        return post("/chat", null, JSON.toJSONString(body));
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:vehicle:list')")
    @GetMapping("/report/{datasetId}")
    public ResponseEntity<String> report(@PathVariable String datasetId, HttpServletRequest request)
    {
        return getDataset(datasetId, "/report/" + MarketAgentGateway.encodePath(datasetId), request.getQueryString());
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:vehicle:export')")
    @PostMapping("/export/{datasetId}/{format}")
    public ResponseEntity<String> export(@PathVariable String datasetId, @PathVariable String format,
        HttpServletRequest request)
    {
        if (!canAccessDataset(datasetId)) return datasetForbidden();
        String query = request.getQueryString();
        try
        {
            AgentResponse exported = gateway.postJson("/export/" + MarketAgentGateway.encodePath(datasetId) + "/"
                + MarketAgentGateway.encodePath(format), query, "{}");
            if (exported.getStatusCode() < 200 || exported.getStatusCode() >= 300) return json(exported);

            JSONObject body = JSON.parseObject(exported.getBody());
            try
            {
                if (knowledgeIngestService == null) throw new IllegalStateException("知识库服务未启用");
                String fileName = body.getString("file_name");
                if (fileName == null || fileName.isBlank()
                    || !Path.of(fileName).getFileName().toString().equals(fileName))
                    throw new IOException("分析引擎返回的报告文件名无效");

                // The export engine itself uses the deterministic report path.  Fetch the
                // same rule-based report for searchable chunks, avoiding an extra LLM call.
                AgentResponse report = gateway.get("/report/" + MarketAgentGateway.encodePath(datasetId),
                    appendQuery(query, "use_llm=false"));
                if (report.getStatusCode() < 200 || report.getStatusCode() >= 300)
                    throw new IOException("读取导出报告结构失败，HTTP " + report.getStatusCode());
                BinaryResponse binary = gateway.getBinary("/files/" + MarketAgentGateway.encodePath(fileName));
                if (binary.getStatusCode() < 200 || binary.getStatusCode() >= 300)
                    throw new IOException("读取导出报告文件失败，HTTP " + binary.getStatusCode());

                String scope = (query == null ? "" : query) + "|app=" + body.getString("app_version")
                    + "|export=" + body.getString("report_export_version");
                KnowledgeIngestTask task = knowledgeIngestService.submitGeneratedMarketReport(datasetId, format,
                    fileName, binary.getBody(), report.getBody(), scope, getUsername());
                body.put("knowledge_ingest_status", "2".equals(task.getStatus()) ? "completed" : "submitted");
                body.put("knowledge_task_id", task.getId()); body.put("knowledge_source_id", task.getSourceId());
                body.put("knowledge_source_type", "REPORT");
                body.put("knowledge_message", "报告已提交固定知识库，知识分类为生成报告");
            }
            catch (Exception ingestError)
            {
                // Export/download is the primary workflow.  A knowledge failure is returned
                // as explicit metadata so the UI can warn and a later export can retry it.
                log.error("整车市场报告导出成功，但自动入库失败，datasetId={}，format={}", datasetId, format, ingestError);
                body.put("knowledge_ingest_status", "failed");
                body.put("knowledge_source_type", "REPORT");
                body.put("knowledge_message", "报告下载可用，但自动入库失败："
                    + (ingestError.getMessage() == null ? "未知错误，请重新导出重试" : ingestError.getMessage()));
            }
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body.toJSONString());
        }
        catch (Exception ex)
        {
            log.error("导出整车市场报告失败，datasetId={}，format={}", datasetId, format, ex);
            return unavailable(ex);
        }
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:vehicle:list')")
    @GetMapping("/report-plan/{datasetId}")
    public ResponseEntity<String> reportPlan(@PathVariable String datasetId)
    {
        return getDataset(datasetId, "/report-plan/" + MarketAgentGateway.encodePath(datasetId), null);
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:vehicle:edit')")
    @PostMapping("/report-plan/{datasetId}/instruction")
    public ResponseEntity<String> reportPlanInstruction(@PathVariable String datasetId,
        @RequestBody Map<String, Object> body)
    {
        return postDataset(datasetId, "/report-plan/" + MarketAgentGateway.encodePath(datasetId) + "/instruction", null,
            JSON.toJSONString(body));
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:vehicle:edit')")
    @PutMapping("/report-plan/{datasetId}/visual")
    public ResponseEntity<String> saveVisualReportPlan(@PathVariable String datasetId,
        @RequestBody Map<String, Object> body)
    {
        if (!canAccessDataset(datasetId)) return datasetForbidden();
        try
        {
            return json(gateway.putJson("/report-plan/" + MarketAgentGateway.encodePath(datasetId) + "/visual",
                null, JSON.toJSONString(body)));
        }
        catch (Exception ex)
        {
            return unavailable(ex);
        }
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:vehicle:edit')")
    @PostMapping("/report-plan/{datasetId}/reset")
    public ResponseEntity<String> resetReportPlan(@PathVariable String datasetId)
    {
        return postDataset(datasetId, "/report-plan/" + MarketAgentGateway.encodePath(datasetId) + "/reset", null, "{}");
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:vehicle:list')")
    @GetMapping("/report-config/{datasetId}")
    public ResponseEntity<String> reportConfig(@PathVariable String datasetId)
    {
        return getDataset(datasetId, "/report-config/" + MarketAgentGateway.encodePath(datasetId), null);
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:vehicle:edit')")
    @PutMapping("/report-config/{datasetId}")
    public ResponseEntity<String> updateReportConfig(@PathVariable String datasetId,
        @RequestBody Map<String, Object> body)
    {
        if (!canAccessDataset(datasetId)) return datasetForbidden();
        try { return json(gateway.putJson("/report-config/" + MarketAgentGateway.encodePath(datasetId), null, JSON.toJSONString(body))); }
        catch (Exception ex) { return unavailable(ex); }
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:vehicle:edit')")
    @PostMapping("/report-config/{datasetId}/reset")
    public ResponseEntity<String> resetReportConfig(@PathVariable String datasetId)
    {
        return postDataset(datasetId, "/report-config/" + MarketAgentGateway.encodePath(datasetId) + "/reset", null, "{}");
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:vehicle:query')")
    @GetMapping("/dataset/{datasetId}")
    public ResponseEntity<String> dataset(@PathVariable String datasetId)
    {
        return getDataset(datasetId, "/dataset/" + MarketAgentGateway.encodePath(datasetId), null);
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:vehicle:remove')")
    @DeleteMapping("/dataset/{datasetId}")
    public ResponseEntity<String> deleteDataset(@PathVariable String datasetId)
    {
        if (!canAccessDataset(datasetId)) return datasetForbidden();
        try
        {
            AgentResponse response = gateway.delete("/dataset/" + MarketAgentGateway.encodePath(datasetId));
            if (response.getStatusCode() >= 200 && response.getStatusCode() < 300)
            {
                vehicleAnalysisService.deleteVehicleAnalysisByDatasetId(datasetId);
            }
            return json(response);
        }
        catch (Exception ex) { return unavailable(ex); }
    }

    @PreAuthorize("@ss.hasPermi('business:analysis:vehicle:export')")
    @GetMapping("/files/{fileName:.+}")
    public ResponseEntity<byte[]> download(@PathVariable String fileName)
    {
        if (!Path.of(fileName).getFileName().toString().equals(fileName))
        {
            return ResponseEntity.badRequest().build();
        }
        if (!canDownloadFile(fileName)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        try
        {
            BinaryResponse response = gateway.getBinary("/files/" + MarketAgentGateway.encodePath(fileName));
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.parseMediaType(response.getContentType()));
            if (response.getContentDisposition() != null)
            {
                headers.set(HttpHeaders.CONTENT_DISPOSITION, response.getContentDisposition());
            }
            return new ResponseEntity<>(response.getBody(), headers, HttpStatus.valueOf(response.getStatusCode()));
        }
        catch (Exception ex)
        {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        }
    }

    private ResponseEntity<String> get(String path, String query)
    {
        try { return json(gateway.get(path, query)); }
        catch (Exception ex)
        {
            log.error("调用市场分析服务 GET 失败，path={}，query={}", path, query, ex);
            return unavailable(ex);
        }
    }

    private ResponseEntity<String> post(String path, String query, String body)
    {
        try { return json(gateway.postJson(path, query, body)); }
        catch (Exception ex)
        {
            log.error("调用市场分析服务 POST 失败，path={}，query={}", path, query, ex);
            return unavailable(ex);
        }
    }

    private ResponseEntity<String> getDataset(String datasetId, String path, String query)
    {
        if (!canAccessDataset(datasetId)) return datasetForbidden();
        return get(path, query);
    }

    private ResponseEntity<String> postDataset(String datasetId, String path, String query, String body)
    {
        if (!canAccessDataset(datasetId)) return datasetForbidden();
        return post(path, query, body);
    }

    private String appendQuery(String query, String item)
    {
        return query == null || query.isBlank() ? item : query + "&" + item;
    }

    private boolean canAccessDataset(String datasetId)
    {
        if (datasetId == null || datasetId.isBlank()) return false;
        VehicleAnalysis record = vehicleAnalysisService.selectVehicleAnalysisByDatasetId(datasetId);
        return record != null && (getLoginUser().getUser().isAdmin() || getUsername().equals(record.getCreateBy()));
    }

    private boolean canAccessExcelParseJob(String jobId)
    {
        if (jobId == null || jobId.isBlank()) return false;
        String owner = excelParseJobOwners.get(jobId);
        return owner != null && (getLoginUser().getUser().isAdmin() || getUsername().equals(owner));
    }

    private boolean canDownloadFile(String fileName)
    {
        if (getLoginUser().getUser().isAdmin()) return true;
        VehicleAnalysis filter = new VehicleAnalysis();
        filter.setCreateBy(getUsername());
        for (VehicleAnalysis record : vehicleAnalysisService.selectVehicleAnalysisList(filter))
        {
            if (record.getDatasetId() != null && fileName.contains(record.getDatasetId())) return true;
        }
        return false;
    }

    private ResponseEntity<String> datasetForbidden()
    {
        JSONObject result = new JSONObject();
        result.put("code", 403);
        result.put("msg", "数据集不存在或无权访问");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(result.toJSONString());
    }

    private ResponseEntity<String> parseJobForbidden()
    {
        JSONObject result = new JSONObject();
        result.put("code", 403);
        result.put("msg", "解析任务不存在或无权访问，请重新上传文件");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(result.toJSONString());
    }

    private ResponseEntity<String> json(AgentResponse response)
    {
        if (response.getStatusCode() >= 200 && response.getStatusCode() < 300)
        {
            return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response.getBody());
        }
        String message = response.getBody();
        try
        {
            JSONObject value = JSON.parseObject(response.getBody());
            message = value.getString("detail");
        }
        catch (Exception ignored) { }
        // Python 的 5xx 是上游分析服务异常，用 502 返回并保留 detail；
        // 不再伪装成 RuoYi 自身的通用 500。
        int code = response.getStatusCode() >= 400 && response.getStatusCode() < 500
            ? response.getStatusCode() : 502;
        return gatewayError(message == null || message.isBlank() ? "市场分析服务处理失败" : message, code);
    }

    private ResponseEntity<String> unavailable(Exception ex)
    {
        if (ex instanceof InterruptedException)
        {
            Thread.currentThread().interrupt();
        }
        String detail = ex.getMessage();
        String message = "市场分析服务调用失败，请确认 Market Agent 已启动";
        if (detail != null && !detail.isBlank())
        {
            message += "（" + detail + "）";
        }
        return gatewayError(message, 503);
    }

    private ResponseEntity<String> gatewayError(String message)
    {
        return gatewayError(message, 500);
    }

    private ResponseEntity<String> gatewayError(String message, int code)
    {
        JSONObject result = new JSONObject();
        result.put("code", code);
        result.put("msg", message);
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(result.toJSONString());
    }

    private void saveDatasetMetadata(String body)
    {
        JSONObject parsed = JSON.parseObject(body);
        VehicleAnalysis task = new VehicleAnalysis();
        fillDatasetMetadata(task, parsed);
        task.setStatus("2");
        task.setCreateBy(getUsername());
        task.setRemark("Python确定性解析完成");
        vehicleAnalysisService.insertVehicleAnalysis(task);
    }

    private void fillDatasetMetadata(VehicleAnalysis task, JSONObject parsed)
    {
        task.setDatasetId(parsed.getString("dataset_id"));
        task.setTaskName(parsed.getString("file_name"));
        task.setFileName(parsed.getString("file_name"));
        task.setSourceFilesJson(JSON.toJSONString(parsed.getJSONArray("source_files")));
        task.setRowCount(parsed.getLong("rows"));
        JSONObject summary = parsed.getJSONObject("parse_summary");
        task.setSheetCount(summary == null ? 0 : summary.getInteger("sheet_count"));
        task.setIssueCount(parsed.getJSONArray("issues") == null ? 0 : parsed.getJSONArray("issues").size());
        task.setParserVersion(PARSER_VERSION);
    }
}
