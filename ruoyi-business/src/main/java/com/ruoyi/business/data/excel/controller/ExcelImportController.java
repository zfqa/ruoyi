package com.ruoyi.business.data.excel.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import jakarta.servlet.http.HttpServletResponse;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.security.access.prepost.PreAuthorize;
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
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.config.RuoYiConfig;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.StringUtils;
import com.ruoyi.common.utils.file.FileUtils;
import com.ruoyi.common.utils.file.FileUploadUtils;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.business.data.excel.domain.ExcelImport;
import com.ruoyi.business.data.excel.service.ExcelFileStorage;
import com.ruoyi.business.data.excel.service.IExcelImportService;
import com.ruoyi.business.knowledge.domain.KnowledgeIngestTask;
import com.ruoyi.business.knowledge.service.KnowledgeIngestService;
import com.ruoyi.business.knowledge.service.LlmRuntimeConfiguration;
import com.ruoyi.business.report.domain.AiReport;
import com.ruoyi.business.report.service.IAiReportService;

/**
 * Excel/CSV导入与字段映射 控制器
 * 
 * @author ruoyi
 */
@RestController
@RequestMapping("/business/data/excel")
public class ExcelImportController extends BaseController
{
    @Autowired
    private IExcelImportService excelImportService;

    @Autowired
    private ExcelFileStorage excelFileStorage;

    @Autowired
    private IAiReportService aiReportService;

    @Autowired
    private KnowledgeIngestService knowledgeIngestService;

    @Autowired
    private LlmRuntimeConfiguration llmConfiguration;

    @Autowired
    @Qualifier("excelParseTaskExecutor")
    private ThreadPoolTaskExecutor excelParseTaskExecutor;

    @Value("${business.excel.parse-timeout-minutes:30}")
    private long parseTimeoutMinutes;

    @Value("${business.knowledge.auto-ingest-reports:true}")
    private boolean autoIngestReports;

    @Value("${business.excel.llm.table-timeout-seconds:30}")
    private int llmTableTimeoutSeconds;

    @Value("${business.excel.llm.report-timeout-seconds:180}")
    private int llmReportTimeoutSeconds;

    @Value("${business.excel.llm.max-retries:0}")
    private int llmMaxRetries;

    @Value("${business.excel.llm.max-table-calls:4}")
    private int llmMaxTableCalls;

    @PreAuthorize("@ss.hasPermi('business:data:excel:list')")
    @GetMapping("/list")
    public TableDataInfo list(ExcelImport excelImport)
    {
        startPage();
        List<ExcelImport> list = excelImportService.selectExcelImportList(excelImport);
        for (ExcelImport item : list)
        {
            item.setResultJson(null);
        }
        return getDataTable(list);
    }

    @PreAuthorize("@ss.hasPermi('business:data:excel:export')")
    @Log(title = "Excel/CSV导入与字段映射", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, ExcelImport excelImport)
    {
        List<ExcelImport> list = excelImportService.selectExcelImportList(excelImport);
        ExcelUtil<ExcelImport> util = new ExcelUtil<ExcelImport>(ExcelImport.class);
        util.exportExcel(response, list, "Excel/CSV导入与字段映射数据");
    }

    @PreAuthorize("@ss.hasPermi('business:data:excel:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        ExcelImport task = excelImportService.selectExcelImportStatusById(id);
        if (task != null)
        {
            task.setResultJson(null);
        }
        return success(task);
    }

    @PreAuthorize("@ss.hasPermi('business:data:excel:query')")
    @GetMapping(value = "/result/{id}")
    public AjaxResult getResult(@PathVariable("id") Long id)
    {
        ExcelImport task = excelImportService.selectExcelImportById(id);
        if (task == null)
        {
            return AjaxResult.error("解析结果不存在");
        }
        if (StringUtils.isNotEmpty(task.getResultJson()))
        {
            return success(buildResponsePreview(task, task.getResultJson()));
        }
        Path resultFile = excelFileStorage.findLegacyResultFile(id);
        if (Files.exists(resultFile))
        {
            try
            {
                return success(buildResponsePreview(task, Files.readString(resultFile, StandardCharsets.UTF_8)));
            }
            catch (IOException e)
            {
                return AjaxResult.error("解析结果读取失败: " + e.getMessage());
            }
        }
        return AjaxResult.error("解析结果不存在");
    }

    @PreAuthorize("@ss.hasPermi('business:data:excel:add')")
    @Log(title = "Excel/CSV导入与字段映射", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody ExcelImport excelImport)
    {
        return toAjax(excelImportService.insertExcelImport(excelImport));
    }

    @PreAuthorize("@ss.hasPermi('business:data:excel:edit')")
    @Log(title = "Excel/CSV导入与字段映射", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody ExcelImport excelImport)
    {
        return toAjax(excelImportService.updateExcelImport(excelImport));
    }

    @PreAuthorize("@ss.hasPermi('business:data:excel:remove')")
    @Log(title = "Excel/CSV导入与字段映射", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        for (Long id : ids)
        {
            ExcelImport task = excelImportService.selectExcelImportStatusById(id);
            if (task != null && "1".equals(task.getStatus()))
            {
                return AjaxResult.error("任务 " + id + " 正在解析，暂不能删除");
            }
        }
        return toAjax(excelImportService.deleteExcelImportByIds(ids));
    }

    @PreAuthorize("@ss.hasPermi('business:data:excel:add')")
    @Log(title = "Excel/CSV导入与字段映射", businessType = BusinessType.INSERT)
    @PostMapping("/upload")
    public AjaxResult uploadFile(@RequestParam("file") MultipartFile file) throws Exception
    {
        try
        {
            String filePath = RuoYiConfig.getImportPath();
            String fileName = FileUploadUtils.upload(filePath, file, new String[] { "xlsx", "xlsm", "csv" }, true);
            AjaxResult ajax = AjaxResult.success();
            ajax.put("url", fileName);
            ajax.put("fileName", fileName);
            ajax.put("newFileName", FileUtils.getName(fileName));
            ajax.put("originalFilename", file.getOriginalFilename());
            return ajax;
        }
        catch (Exception e)
        {
            return AjaxResult.error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:data:excel:query')")
    @PostMapping("/parse-local")
    public AjaxResult parseLocal(@RequestBody Map<String, Object> payload) throws IOException
    {
        String filePath = stringValue(payload.get("filePath"));
        if (StringUtils.isEmpty(filePath))
        {
            return AjaxResult.error("filePath不能为空");
        }
        Path file = excelFileStorage.resolveLocalImportFile(filePath);
        String baselineFilePath = stringValue(payload.get("baselineFilePath"));
        Path baselineFile = StringUtils.isEmpty(baselineFilePath)
            ? null : excelFileStorage.resolveLocalImportFile(baselineFilePath);
        String supplyChainFilePath = stringValue(payload.get("supplyChainFilePath"));
        Path supplyChainFile = StringUtils.isEmpty(supplyChainFilePath)
            ? null : excelFileStorage.resolveLocalImportFile(supplyChainFilePath);
        ParseOptions options = parseOptions(payload);
        return submitParse(file.toString(), file.getFileName().toString(), file.toString(),
            baselineFile == null ? null : baselineFile.toString(),
            supplyChainFile == null ? null : supplyChainFile.toString(),
            List.of(), List.of(), options);
    }

    @PreAuthorize("@ss.hasPermi('business:data:excel:query')")
    @PostMapping("/parse-upload")
    public AjaxResult parseUpload(@RequestBody Map<String, Object> payload) throws IOException
    {
        String fileName = stringValue(payload.get("fileName"));
        if (StringUtils.isEmpty(fileName))
        {
            return AjaxResult.error("fileName不能为空");
        }

        Path absolutePath = excelFileStorage.resolveUploadedFile(fileName);
        String baselineFileName = stringValue(payload.get("baselineFileName"));
        Path baselinePath = StringUtils.isEmpty(baselineFileName)
            ? null : excelFileStorage.resolveUploadedFile(baselineFileName);
        String supplyChainFileName = stringValue(payload.get("supplyChainFileName"));
        Path supplyChainPath = StringUtils.isEmpty(supplyChainFileName)
            ? null : excelFileStorage.resolveUploadedFile(supplyChainFileName);
        List<String> extraHistoryPaths = resolveUploadedPathList(payload.get("extraHistoryFileNames"));
        List<String> extraSupplyPaths = resolveUploadedPathList(payload.get("extraSupplyChainFileNames"));
        ParseOptions options = parseOptions(payload);
        return submitParse(absolutePath.toString(), absolutePath.getFileName().toString(), fileName,
            baselinePath == null ? null : baselinePath.toString(),
            supplyChainPath == null ? null : supplyChainPath.toString(),
            extraHistoryPaths, extraSupplyPaths, options);
    }

    private List<String> resolveUploadedPathList(Object raw) throws IOException
    {
        List<String> paths = new ArrayList<>();
        if (!(raw instanceof List<?> list))
        {
            return paths;
        }
        for (Object item : list)
        {
            String name = stringValue(item);
            if (StringUtils.isEmpty(name))
            {
                continue;
            }
            paths.add(excelFileStorage.resolveUploadedFile(name).toString());
        }
        return paths;
    }

    private AjaxResult submitParse(String absolutePath, String displayFileName, String storedFilePath,
        String baselineAbsolutePath, String supplyChainAbsolutePath,
        List<String> extraHistoryAbsolutePaths, List<String> extraSupplyAbsolutePaths,
        ParseOptions options)
    {
        ExcelImport task = new ExcelImport();
        task.setTaskName(displayFileName);
        task.setFileName(displayFileName);
        task.setFilePath(storedFilePath);
        task.setStatus("1");
        task.setRemark("解析任务排队中");
        excelImportService.insertExcelImport(task);

        try
        {
            final List<String> historyExtras = extraHistoryAbsolutePaths == null
                ? List.of() : List.copyOf(extraHistoryAbsolutePaths);
            final List<String> supplyExtras = extraSupplyAbsolutePaths == null
                ? List.of() : List.copyOf(extraSupplyAbsolutePaths);
            excelParseTaskExecutor.execute(() -> executeParse(
                task.getId(), absolutePath, baselineAbsolutePath, supplyChainAbsolutePath,
                historyExtras, supplyExtras, options));
        }
        catch (RuntimeException ex)
        {
            task.setStatus("3");
            String message = "解析队列已满，请稍后重试";
            task.setRemark(StringUtils.substring(message, 0, 500));
            excelImportService.updateExcelImport(task);
            AjaxResult ajax = AjaxResult.error(message);
            ajax.put("taskId", task.getId());
            return ajax;
        }
        JSONObject status = buildTaskStatus(task);
        return AjaxResult.success("解析任务已提交", status);
    }

    private void executeParse(Long taskId, String absolutePath, String baselineAbsolutePath,
        String supplyChainAbsolutePath, List<String> extraHistoryAbsolutePaths,
        List<String> extraSupplyAbsolutePaths, ParseOptions options)
    {
        ExcelImport task = excelImportService.selectExcelImportById(taskId);
        if (task == null)
        {
            return;
        }
        try
        {
            task.setRemark("正在解析工作簿（LLM候选表调用上限: " + llmMaxTableCalls + "）");
            excelImportService.updateExcelImport(task);
            String output = runAgent(absolutePath, baselineAbsolutePath, supplyChainAbsolutePath,
                extraHistoryAbsolutePaths, extraSupplyAbsolutePaths, options);
            fillParseSummary(task, output);
            task.setResultJson(output);
            task.setStatus("1");
            task.setRemark("解析完成，正在生成第一份竞争社洞察报告");
            excelImportService.updateExcelImport(task);
            // Avoid rewriting multi-MB result_json on later status updates — that
            // row-locks status polling and trips the frontend 10s axios timeout.
            task.setResultJson(null);
            AiReport report = generateFirstReport(task, output, options);
            task.setStatus("2");
            task.setRemark("2".equals(report.getStatus())
                ? "解析成功，第一份报告已生成（报告ID: " + report.getId() + "）"
                : "解析成功，但第一份报告生成失败（报告ID: " + report.getId() + "）");
            excelImportService.updateExcelImport(task);
        }
        catch (Exception ex)
        {
            if (ex instanceof InterruptedException)
            {
                Thread.currentThread().interrupt();
            }
            task.setStatus("3");
            String message = ex.getMessage() == null ? "解析失败" : ex.getMessage();
            task.setRemark(StringUtils.substring(message, 0, 500));
            excelImportService.updateExcelImport(task);
        }
    }

    @PreAuthorize("@ss.hasPermi('business:data:excel:query')")
    @GetMapping(value = "/status/{id}")
    public AjaxResult getStatus(@PathVariable("id") Long id)
    {
        ExcelImport task = excelImportService.selectExcelImportStatusById(id);
        return task == null ? AjaxResult.error("解析任务不存在") : success(buildTaskStatus(task));
    }

    private JSONObject buildTaskStatus(ExcelImport task)
    {
        JSONObject status = new JSONObject();
        status.put("taskId", task.getId());
        status.put("status", task.getStatus());
        status.put("remark", task.getRemark());
        status.put("sheet_count", task.getSheetCount());
        status.put("table_count", task.getTableCount());
        status.put("record_count", task.getRecordCount());
        status.put("stage", taskStage(task));
        status.put("progress", taskProgress(task));
        AiReport report = aiReportService.selectLatestAiReportByImportTaskId(task.getId());
        if (report != null)
        {
            status.put("report_id", report.getId());
            status.put("report_status", report.getStatus());
        }
        return status;
    }

    private java.io.File resolveAgentScript()
    {
        java.io.File current = new java.io.File(System.getProperty("user.dir"));
        for (int i = 0; i < 5 && current != null; i++)
        {
            java.io.File candidate = new java.io.File(current, "ruoyi-business/excel-agent/parse_workbook.py");
            if (candidate.exists())
            {
                return candidate;
            }
            current = current.getParentFile();
        }
        return new java.io.File(System.getProperty("user.dir"), "ruoyi-business/excel-agent/parse_workbook.py");
    }

    private java.io.File resolveReportAgentScript()
    {
        java.io.File current = new java.io.File(System.getProperty("user.dir"));
        for (int i = 0; i < 5 && current != null; i++)
        {
            java.io.File candidate = new java.io.File(current, "ruoyi-business/excel-agent/generate_report.py");
            if (candidate.exists())
            {
                return candidate;
            }
            current = current.getParentFile();
        }
        return new java.io.File(System.getProperty("user.dir"), "ruoyi-business/excel-agent/generate_report.py");
    }

    private AiReport generateFirstReport(ExcelImport task, String parsedJson, ParseOptions options)
    {
        AiReport report = new AiReport();
        report.setImportTaskId(task.getId());
        report.setTaskName("竞争社洞察 - " + task.getFileName());
        report.setReportType("competitive_insight_v1");
        report.setGenerationMode(options.useLlm ? "python_metrics_llm_narrative" : "python_metrics_template");
        report.setStatus("1");
        report.setRemark("正在按照竞争社洞察数据逻辑生成第一份报告");
        aiReportService.insertAiReport(report);
        try
        {
            String reportJson = runReportAgent(parsedJson, options.useLlm);
            report.setReportContent(reportJson);
            report.setStatus("2");
            report.setRemark("报告生成成功");
        }
        catch (Exception ex)
        {
            if (ex instanceof InterruptedException)
            {
                Thread.currentThread().interrupt();
            }
            report.setStatus("3");
            String message = ex.getMessage() == null ? "报告生成失败" : ex.getMessage();
            report.setRemark(StringUtils.substring(message, 0, 500));
        }
        aiReportService.updateAiReport(report);
        if (autoIngestReports && "2".equals(report.getStatus()))
        {
            try
            {
                String creator = StringUtils.isEmpty(task.getCreateBy()) ? "system" : task.getCreateBy();
                KnowledgeIngestTask knowledgeTask = knowledgeIngestService.submitGeneratedReport(report.getId(), creator);
                report.setRemark("报告生成成功，知识入库任务已提交（任务ID: " + knowledgeTask.getId() + "）");
            }
            catch (Exception ex)
            {
                String message = ex.getMessage() == null ? "未知错误" : ex.getMessage();
                report.setRemark(StringUtils.substring("报告生成成功；知识入库提交失败：" + message, 0, 500));
            }
            aiReportService.updateAiReport(report);
        }
        return report;
    }

    private String runReportAgent(String parsedJson, boolean useLlm) throws IOException, InterruptedException
    {
        java.io.File agent = resolveReportAgentScript();
        if (!agent.exists())
        {
            throw new IOException("报告生成器脚本不存在: " + agent.getPath());
        }
        Path inputFile = Files.createTempFile("excel-report-input-", ".json");
        try
        {
            Files.writeString(inputFile, parsedJson, StandardCharsets.UTF_8);
            PythonLaunchException lastError = null;
            for (String python : pythonCandidates())
            {
                try
                {
                    return runReportAgentWithPython(python, agent, inputFile, useLlm);
                }
                catch (PythonLaunchException ex)
                {
                    lastError = ex;
                }
            }
            throw lastError == null ? new IOException("未找到可用Python解释器") : lastError;
        }
        finally
        {
            deleteTempFile(inputFile);
        }
    }

    private String runReportAgentWithPython(String python, java.io.File agent, Path inputFile, boolean useLlm)
        throws IOException, InterruptedException
    {
        List<String> command = new ArrayList<>();
        command.add(python);
        command.add(agent.getPath());
        command.add(inputFile.toString());
        if (!useLlm)
        {
            command.add("--no-llm");
        }
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("PYTHONUTF8", "1");
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        pb.environment().put("ARK_API_URL", llmConfiguration.getApiUrl());
        pb.environment().put("ARK_MODEL", llmConfiguration.getModel());
        applyLlmLimits(pb, llmReportTimeoutSeconds);
        if (StringUtils.isNotEmpty(llmConfiguration.getApiKey()))
        {
            pb.environment().put("ARK_API_KEY", llmConfiguration.getApiKey());
        }
        pb.redirectErrorStream(true);
        Path outputFile = Files.createTempFile("excel-report-output-", ".json");
        try
        {
            pb.redirectOutput(outputFile.toFile());
            Process process = startPython(pb, python);
            boolean finished = process.waitFor(parseTimeoutMinutes, TimeUnit.MINUTES);
            if (!finished)
            {
                stopProcess(process);
                throw new IOException("报告生成超时");
            }
            String output = Files.readString(outputFile, StandardCharsets.UTF_8);
            if (process.exitValue() != 0)
            {
                throw new IOException(output);
            }
            JSON.parseObject(output);
            return output;
        }
        finally
        {
            deleteTempFile(outputFile);
        }
    }

    private String runAgent(String absolutePath, String baselineAbsolutePath,
        String supplyChainAbsolutePath, List<String> extraHistoryAbsolutePaths,
        List<String> extraSupplyAbsolutePaths, ParseOptions options)
        throws IOException, InterruptedException
    {
        java.io.File agent = resolveAgentScript();
        if (!agent.exists())
        {
            throw new IOException("解析器脚本不存在: " + agent.getPath());
        }

        PythonLaunchException lastError = null;
        for (String python : pythonCandidates())
        {
            try
            {
                return runAgentWithPython(
                    python, agent, absolutePath, baselineAbsolutePath, supplyChainAbsolutePath,
                    extraHistoryAbsolutePaths, extraSupplyAbsolutePaths, options);
            }
            catch (PythonLaunchException ex)
            {
                lastError = ex;
            }
        }
        throw lastError == null ? new IOException("未找到可用Python解释器") : lastError;
    }

    private String runAgentWithPython(String python, java.io.File agent, String absolutePath,
        String baselineAbsolutePath, String supplyChainAbsolutePath,
        List<String> extraHistoryAbsolutePaths, List<String> extraSupplyAbsolutePaths,
        ParseOptions options)
        throws IOException, InterruptedException
    {
        List<String> command = new ArrayList<>();
        command.add(python);
        command.add(agent.getPath());
        command.add(absolutePath);
        command.add("--max-records-per-table");
        command.add("50");
        if (StringUtils.isNotEmpty(baselineAbsolutePath))
        {
            command.add("--baseline-file");
            command.add(baselineAbsolutePath);
        }
        if (StringUtils.isNotEmpty(supplyChainAbsolutePath))
        {
            command.add("--supply-chain-file");
            command.add(supplyChainAbsolutePath);
        }
        if (extraHistoryAbsolutePaths != null)
        {
            for (String path : extraHistoryAbsolutePaths)
            {
                if (StringUtils.isNotEmpty(path))
                {
                    command.add("--extra-history-file");
                    command.add(path);
                }
            }
        }
        if (extraSupplyAbsolutePaths != null)
        {
            for (String path : extraSupplyAbsolutePaths)
            {
                if (StringUtils.isNotEmpty(path))
                {
                    command.add("--extra-supply-chain-file");
                    command.add(path);
                }
            }
        }
        if (StringUtils.isNotEmpty(options.fileOriginalName))
        {
            command.add("--file-label");
            command.add(options.fileOriginalName);
        }
        if (StringUtils.isNotEmpty(options.baselineOriginalName))
        {
            command.add("--baseline-file-label");
            command.add(options.baselineOriginalName);
        }
        if (StringUtils.isNotEmpty(options.supplyChainOriginalName))
        {
            command.add("--supply-chain-file-label");
            command.add(options.supplyChainOriginalName);
        }
        for (String label : options.extraHistoryOriginalNames)
        {
            if (StringUtils.isNotEmpty(label))
            {
                command.add("--extra-history-file-label");
                command.add(label);
            }
        }
        for (String label : options.extraSupplyChainOriginalNames)
        {
            if (StringUtils.isNotEmpty(label))
            {
                command.add("--extra-supply-chain-file-label");
                command.add(label);
            }
        }
        if (options.includeRawCells)
        {
            command.add("--include-raw-cells");
            command.add("--raw-cell-mode");
            command.add(options.rawCellMode);
        }
        if (options.useLlm)
        {
            command.add("--use-llm");
            command.add("--max-llm-tables");
            command.add(String.valueOf(llmMaxTableCalls));
        }
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.environment().put("PYTHONUTF8", "1");
        pb.environment().put("PYTHONIOENCODING", "utf-8");
        if (options.useLlm)
        {
            pb.environment().put("ARK_API_URL", llmConfiguration.getApiUrl());
            pb.environment().put("ARK_MODEL", llmConfiguration.getModel());
            applyLlmLimits(pb, llmTableTimeoutSeconds);
            if (StringUtils.isNotEmpty(llmConfiguration.getApiKey()))
            {
                pb.environment().put("ARK_API_KEY", llmConfiguration.getApiKey());
            }
        }
        pb.directory(new java.io.File("."));
        pb.redirectErrorStream(true);
        Path outputFile = Files.createTempFile("excel-agent-", ".json");
        try
        {
            pb.redirectOutput(outputFile.toFile());
            Process process = startPython(pb, python);
            boolean finished;
            try
            {
                finished = process.waitFor(parseTimeoutMinutes, TimeUnit.MINUTES);
            }
            catch (InterruptedException ex)
            {
                stopProcess(process);
                throw ex;
            }
            if (!finished)
            {
                stopProcess(process);
                throw new IOException("解析超过 " + parseTimeoutMinutes + " 分钟，任务已终止且不会自动重跑");
            }
            String output = Files.readString(outputFile, StandardCharsets.UTF_8);
            int exitCode = process.exitValue();
            if (exitCode != 0)
            {
                throw new IOException(output);
            }
            JSON.parseObject(output);
            return output;
        }
        finally
        {
            deleteTempFile(outputFile);
        }
    }

    private List<String> pythonCandidates()
    {
        Set<String> candidates = new LinkedHashSet<>();
        String configured = System.getProperty("excel.agent.python");
        if (StringUtils.isNotEmpty(configured))
        {
            candidates.add(configured);
        }
        String envPython = System.getenv("PYTHON");
        if (StringUtils.isNotEmpty(envPython))
        {
            candidates.add(envPython);
        }
        // Windows 的 py.exe 可能只有启动器而没有关联解释器；优先使用项目已验证的完整路径，
        // 避免 py 启动后以“No installed Python found”退出并中断后续候选尝试。
        candidates.add("C:\\Users\\10906\\.cache\\codex-runtimes\\codex-primary-runtime\\dependencies\\python\\python.exe");
        candidates.add("C:\\Users\\10906\\AppData\\Local\\Programs\\Python\\Python313\\python.exe");
        candidates.add("python");
        return new ArrayList<>(candidates);
    }

    private Process startPython(ProcessBuilder builder, String python) throws PythonLaunchException
    {
        try
        {
            return builder.start();
        }
        catch (IOException ex)
        {
            throw new PythonLaunchException("无法启动Python解释器 " + python + ": " + ex.getMessage(), ex);
        }
    }

    private void stopProcess(Process process)
    {
        List<ProcessHandle> descendants = process.descendants().toList();
        for (int i = descendants.size() - 1; i >= 0; i--)
        {
            descendants.get(i).destroyForcibly();
        }
        process.destroyForcibly();
        try
        {
            process.waitFor(5, TimeUnit.SECONDS);
        }
        catch (InterruptedException ex)
        {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Windows may keep redirected process output handles briefly after a forced stop.
     * Cleanup is best-effort and must never replace the real parser exception.
     */
    private void deleteTempFile(Path path)
    {
        for (int attempt = 0; attempt < 10; attempt++)
        {
            try
            {
                if (Files.deleteIfExists(path) || !Files.exists(path))
                {
                    return;
                }
            }
            catch (IOException ignored)
            {
                // Retry after the child process has released its inherited file handle.
            }
            try
            {
                Thread.sleep(100L);
            }
            catch (InterruptedException ex)
            {
                Thread.currentThread().interrupt();
                break;
            }
        }
        path.toFile().deleteOnExit();
    }

    private void applyLlmLimits(ProcessBuilder builder, int timeoutSeconds)
    {
        builder.environment().put("ARK_TIMEOUT_SECONDS", String.valueOf(timeoutSeconds));
        builder.environment().put("ARK_MAX_RETRIES", String.valueOf(llmMaxRetries));
    }

    private String taskStage(ExcelImport task)
    {
        if ("2".equals(task.getStatus()))
        {
            return "completed";
        }
        if ("3".equals(task.getStatus()))
        {
            return "failed";
        }
        String remark = StringUtils.defaultString(task.getRemark());
        if (remark.contains("报告"))
        {
            return "report";
        }
        return remark.contains("排队") ? "queued" : "parsing";
    }

    private int taskProgress(ExcelImport task)
    {
        String stage = taskStage(task);
        if ("completed".equals(stage) || "failed".equals(stage))
        {
            return 100;
        }
        if ("report".equals(stage))
        {
            return 80;
        }
        return "parsing".equals(stage) ? 15 : 5;
    }

    private ParseOptions parseOptions(Map<String, Object> payload)
    {
        boolean includeRawCells = Boolean.parseBoolean(stringValue(payload.getOrDefault("includeRawCells", "false")));
        String rawCellMode = stringValue(payload.getOrDefault("rawCellMode", "non-empty"));
        boolean useLlm = Boolean.parseBoolean(stringValue(payload.getOrDefault("useLlm", "true")));
        if (!"all".equals(rawCellMode))
        {
            rawCellMode = "non-empty";
        }
        return new ParseOptions(
            includeRawCells,
            rawCellMode,
            useLlm,
            stringValue(payload.get("fileOriginalName")),
            stringValue(payload.get("baselineOriginalName")),
            stringValue(payload.get("supplyChainOriginalName")),
            stringList(payload.get("extraHistoryOriginalNames")),
            stringList(payload.get("extraSupplyChainOriginalNames"))
        );
    }

    private List<String> stringList(Object raw)
    {
        List<String> values = new ArrayList<>();
        if (!(raw instanceof List<?> list))
        {
            return values;
        }
        for (Object item : list)
        {
            String value = stringValue(item);
            if (StringUtils.isNotEmpty(value))
            {
                values.add(value);
            }
        }
        return values;
    }

    private String stringValue(Object value)
    {
        return value == null ? null : String.valueOf(value);
    }

    private static class ParseOptions
    {
        private final boolean includeRawCells;
        private final String rawCellMode;
        private final boolean useLlm;
        private final String fileOriginalName;
        private final String baselineOriginalName;
        private final String supplyChainOriginalName;
        private final List<String> extraHistoryOriginalNames;
        private final List<String> extraSupplyChainOriginalNames;

        private ParseOptions(
            boolean includeRawCells,
            String rawCellMode,
            boolean useLlm,
            String fileOriginalName,
            String baselineOriginalName,
            String supplyChainOriginalName,
            List<String> extraHistoryOriginalNames,
            List<String> extraSupplyChainOriginalNames)
        {
            this.includeRawCells = includeRawCells;
            this.rawCellMode = rawCellMode;
            this.useLlm = useLlm;
            this.fileOriginalName = fileOriginalName;
            this.baselineOriginalName = baselineOriginalName;
            this.supplyChainOriginalName = supplyChainOriginalName;
            this.extraHistoryOriginalNames = extraHistoryOriginalNames == null
                ? List.of() : List.copyOf(extraHistoryOriginalNames);
            this.extraSupplyChainOriginalNames = extraSupplyChainOriginalNames == null
                ? List.of() : List.copyOf(extraSupplyChainOriginalNames);
        }
    }

    private static class PythonLaunchException extends IOException
    {
        private static final long serialVersionUID = 1L;

        private PythonLaunchException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }

    private void fillParseSummary(ExcelImport task, String output)
    {
        JSONObject root = JSON.parseObject(output);
        JSONArray sheets = root.getJSONArray("sheets");
        int tableCount = 0;
        int recordCount = 0;
        if (sheets != null)
        {
            for (int i = 0; i < sheets.size(); i++)
            {
                JSONObject sheet = sheets.getJSONObject(i);
                JSONArray tables = sheet.getJSONArray("tables");
                if (tables == null)
                {
                    continue;
                }
                tableCount += tables.size();
                for (int j = 0; j < tables.size(); j++)
                {
                    JSONObject table = tables.getJSONObject(j);
                    JSONObject data = table.getJSONObject("data");
                    Integer totalRecords = data == null ? null : data.getInteger("record_count");
                    JSONArray records = table.getJSONArray("records");
                    recordCount += totalRecords == null
                        ? (records == null ? 0 : records.size()) : totalRecords;
                }
            }
        }
        task.setWorkbookId(root.getString("workbook_id"));
        task.setSheetCount(sheets == null ? 0 : sheets.size());
        task.setTableCount(tableCount);
        task.setRecordCount(recordCount);
    }

    private JSONObject buildResponsePreview(ExcelImport task, String output)
    {
        JSONObject root = JSON.parseObject(output);
        JSONArray sheets = root.getJSONArray("sheets");
        JSONArray previewSheets = new JSONArray();
        if (sheets != null)
        {
            for (int i = 0; i < sheets.size(); i++)
            {
                JSONObject sheet = sheets.getJSONObject(i);
                JSONObject previewSheet = new JSONObject();
                previewSheet.put("sheet_name", sheet.getString("sheet_name"));
                previewSheet.put("sheet_index", sheet.getInteger("sheet_index"));
                previewSheet.put("effective_range", sheet.getString("effective_range"));
                previewSheet.put("raw_cell_count", sheet.getJSONArray("cells") == null ? 0 : sheet.getJSONArray("cells").size());

                JSONArray tables = sheet.getJSONArray("tables");
                JSONArray previewTables = new JSONArray();
                if (tables != null)
                {
                    for (int j = 0; j < tables.size(); j++)
                    {
                        JSONObject table = tables.getJSONObject(j);
                        JSONObject previewTable = new JSONObject();
                        previewTable.put("table_id", table.getString("table_id"));
                        previewTable.put("table_name", table.getString("table_name"));
                        previewTable.put("source", table.getJSONObject("source"));
                        previewTable.put("fields", table.getJSONArray("fields"));
                        JSONArray records = table.getJSONArray("records");
                        JSONObject data = table.getJSONObject("data");
                        Integer totalRecords = data == null ? null : data.getInteger("record_count");
                        previewTable.put("record_count", totalRecords == null
                            ? (records == null ? 0 : records.size()) : totalRecords);
                        previewTable.put("records", firstItems(records, 3));
                        previewTables.add(previewTable);
                    }
                }
                previewSheet.put("tables", previewTables);
                previewSheets.add(previewSheet);
            }
        }

        JSONObject response = new JSONObject();
        response.put("taskId", task.getId());
        response.put("workbook_id", task.getWorkbookId());
        response.put("file_name", task.getFileName());
        response.put("sheet_count", task.getSheetCount());
        response.put("table_count", task.getTableCount());
        response.put("record_count", task.getRecordCount());
        response.put("sheets", previewSheets);
        response.put("quality", root.getJSONObject("quality"));
        response.put("full_result_saved", true);
        return response;
    }

    private JSONArray firstItems(JSONArray source, int limit)
    {
        JSONArray result = new JSONArray();
        if (source == null)
        {
            return result;
        }
        for (int i = 0; i < source.size() && i < limit; i++)
        {
            result.add(source.get(i));
        }
        return result;
    }
}
