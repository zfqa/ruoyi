package com.ruoyi.business.knowledge.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.business.agent.client.AgentServiceClient;
import com.ruoyi.business.agent.client.AgentServiceClientException;
import com.ruoyi.business.knowledge.domain.KnowledgeBase;
import com.ruoyi.business.knowledge.domain.KnowledgeChunk;
import com.ruoyi.business.knowledge.domain.KnowledgeFact;
import com.ruoyi.business.knowledge.domain.KnowledgeIngestTask;
import com.ruoyi.business.knowledge.domain.KnowledgeVersion;
import com.ruoyi.business.knowledge.mapper.KnowledgeBaseMapper;
import com.ruoyi.business.report.domain.AiReport;
import com.ruoyi.business.report.service.IAiReportService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class KnowledgeIngestService
{
    private static final String PARSER_VERSION = "kb-fact-1";
    private final KnowledgeFactIndexer factIndexer = new KnowledgeFactIndexer();
    private static final int CHUNK_SIZE = 1200;
    private static final int CHUNK_OVERLAP = 120;

    private final KnowledgeBaseMapper mapper;
    private final KnowledgeFileStorage storage;
    private final IAiReportService reportService;
    private final ThreadPoolTaskExecutor executor;
    private final Set<String> allowedNewsDomains;
    private final HttpClient httpClient;
    private boolean newsIngestEnabled;
    private KnowledgeGraphService graphService;
    private final KnowledgeMetricQueryRouter metricQueryRouter = new KnowledgeMetricQueryRouter();
    private KnowledgeReportProjectionService reportProjectionService;
    private AgentServiceClient agentServiceClient;
    private boolean agentDocumentEnabled;

    public KnowledgeIngestService(KnowledgeBaseMapper mapper, KnowledgeFileStorage storage,
        IAiReportService reportService, @Qualifier("knowledgeTaskExecutor") ThreadPoolTaskExecutor executor,
        @Value("${business.knowledge.news-allowed-domains:}") String allowedNewsDomains)
    {
        this.mapper = mapper;
        this.storage = storage;
        this.reportService = reportService;
        this.executor = executor;
        this.allowedNewsDomains = Arrays.stream(allowedNewsDomains.split(","))
            .map(String::trim).map(s -> s.toLowerCase(Locale.ROOT)).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Value("${business.knowledge.news-ingest-enabled:false}")
    public void setNewsIngestEnabled(boolean newsIngestEnabled)
    {
        this.newsIngestEnabled = newsIngestEnabled;
    }

    @Autowired
    public void setGraphService(KnowledgeGraphService graphService)
    {
        this.graphService = graphService;
    }

    @Autowired
    public void setReportProjectionService(KnowledgeReportProjectionService reportProjectionService)
    {
        this.reportProjectionService = reportProjectionService;
    }

    @Autowired(required = false)
    public void setAgentServiceClient(AgentServiceClient agentServiceClient) { this.agentServiceClient = agentServiceClient; }

    @Value("${business.knowledge.agent-document-enabled:true}")
    public void setAgentDocumentEnabled(boolean enabled) { this.agentDocumentEnabled = enabled; }

    public KnowledgeIngestTask submitPdf(Long sourceId, String versionNo, MultipartFile file, String username) throws IOException
    {
        KnowledgeBase source = requireSource(sourceId, "PDF");
        String uploadedName = file == null ? "" : firstNonBlank(file.getOriginalFilename());
        if (!uploadedName.toLowerCase(Locale.ROOT).endsWith(".pdf")) throw new IOException("PDF知识源仅支持PDF文件");
        KnowledgeFileStorage.StoredFile stored = agentDocumentEnabled ? storage.saveDocument(file) : storage.savePdf(file);
        try
        {
            KnowledgeVersion version = createVersion(source, versionNo, stored.originalName(), stored.path().toString(), "", stored.sha256(), username);
            return submit(version, username, () -> processPdf(source, version));
        }
        catch (RuntimeException e)
        {
            storage.delete(stored.path());
            throw e;
        }
    }

    /**
     * Publish a successful PDF/PPTX parsing snapshot without invoking Python again.
     * The original parsing task remains the workflow record; MySQL knowledge tables
     * are the only published knowledge store.
     */
    public synchronized KnowledgeIngestTask submitParsedDocument(Long originTaskId, String taskName,
        String originalName, Path sourceFile, JSONObject parsedResult, String username) throws IOException
    {
        if (originTaskId == null || parsedResult == null) throw new IllegalArgumentException("文档解析结果为空，无法发布知识库");
        if (!"success".equalsIgnoreCase(parsedResult.getString("status")) || !parsedResult.getBooleanValue("supported"))
            throw new IllegalArgumentException("仅允许发布解析成功的文档");
        String sourceCode = "DATA-PDF-" + originTaskId;
        KnowledgeBase source = mapper.selectKnowledgeBaseBySourceCode(sourceCode);
        if (source == null)
        {
            source = new KnowledgeBase(); source.setSourceCode(sourceCode);
            source.setSourceName(firstNonBlank(taskName, stringName(sourceFile), sourceCode)); source.setSourceType("PDF");
            source.setConfidentiality("INTERNAL"); source.setAllowedPurpose("知识问答、文档分析及来源追溯");
            source.setAllowedRoleIds(""); source.setEnabled("1"); source.setStatus("0"); source.setCreateBy(username);
            mapper.insertKnowledgeBase(source);
        }
        KnowledgeFileStorage.StoredFile stored = storage.importDocument(sourceFile, sanitizeOriginalName(originalName));
        KnowledgeVersion existing = mapper.selectVersionByHash(source.getId(), stored.sha256());
        if (existing != null)
        {
            storage.delete(stored.path());
            KnowledgeIngestTask existingTask = mapper.selectIngestTaskByVersionId(existing.getId());
            if (existingTask != null) return existingTask;
            throw new IllegalStateException("该文档版本已存在");
        }
        String versionNo = "document-" + originTaskId + "-" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        KnowledgeVersion version = createVersion(source, versionNo, stored.originalName(), stored.path().toString(), "",
            stored.sha256(), username);
        KnowledgeBase actualSource = source;
        return submit(version, username, () -> processAgentResult(actualSource, version, parsedResult));
    }

    public KnowledgeIngestTask submitNews(Long sourceId, String versionNo, String url, String title, String username) throws Exception
    {
        return submitNews(sourceId, versionNo, url, title, "", username);
    }

    public KnowledgeIngestTask submitNews(Long sourceId, String versionNo, String url, String title,
        String content, String username) throws Exception
    {
        if (!newsIngestEnabled)
            throw new IllegalStateException("新闻抓取接口已预留，当前POC未启用");
        KnowledgeBase source = requireSource(sourceId, "NEWS");
        URI uri = validateExternalUri(url);
        String actualTitle = title == null || title.isBlank() ? source.getSourceName() : title.trim();
        if (content != null && !content.isBlank())
        {
            String normalized = normalizeText(content);
            if (normalized.length() < 20) throw new IllegalArgumentException("新闻正文至少20个字符");
            String hash = storage.sha256(normalized);
            KnowledgeVersion version = createVersion(source, versionNo, actualTitle, "", uri.toString(), hash, username);
            return submit(version, username, () -> processNewsText(source, version, actualTitle, normalized));
        }
        validateNewsUri(uri);
        String provisionalHash = storage.sha256("PENDING:" + uri + ":" + System.nanoTime());
        KnowledgeVersion version = createVersion(source, versionNo, actualTitle, "", uri.toString(), provisionalHash, username);
        return submit(version, username, () -> processNews(source, version, actualTitle, uri));
    }

    /**
     * Publish one crawler article into the existing unified knowledge tables.
     * Each article owns a stable source so updates create versions instead of replacing another article.
     */
    public synchronized KnowledgeIngestTask submitCollectedNews(Long articleId, String sourceName, String sourceSite,
        String title, String content, String url, String publishedAt, String crawledAt, String contentHash,
        String username) throws Exception
    {
        if (articleId == null) throw new IllegalArgumentException("新闻文章ID不能为空");
        String normalized = normalizeText(content);
        if (normalized.length() < 20) throw new IllegalArgumentException("新闻正文至少20个字符");
        URI uri = validateExternalUri(url);
        String actualHash = clean(contentHash).isBlank() ? storage.sha256(normalized) : clean(contentHash);
        String sourceCode = "NEWS-ARTICLE-" + articleId;
        KnowledgeBase source = mapper.selectKnowledgeBaseBySourceCode(sourceCode);
        if (source == null)
        {
            source = new KnowledgeBase(); source.setSourceCode(sourceCode);
            source.setSourceName(firstNonBlank(title, sourceName, sourceCode)); source.setSourceType("NEWS");
            source.setConfidentiality("INTERNAL"); source.setAllowedPurpose("知识问答、新闻分析及来源追溯");
            source.setAllowedRoleIds(""); source.setEnabled("1"); source.setStatus("0"); source.setCreateBy(username);
            mapper.insertKnowledgeBase(source);
        }
        KnowledgeVersion existing = mapper.selectVersionByHash(source.getId(), actualHash);
        if (existing != null)
        {
            KnowledgeIngestTask existingTask = mapper.selectIngestTaskByVersionId(existing.getId());
            if (existingTask != null && "2".equals(existingTask.getStatus()) && source.getCurrentVersionId() != null)
                return existingTask;
            // Resume incomplete/failed collected-news ingest synchronously.
            KnowledgeBase actualSource = source;
            KnowledgeVersion resumeVersion = existing;
            return submitSync(resumeVersion, username,
                () -> processCollectedNews(actualSource, resumeVersion, title, normalized, buildNewsEvidence(
                    articleId, sourceName, sourceSite, publishedAt, crawledAt, uri.toString(), actualHash).toJSONString()));
        }
        String versionNo = "news-" + articleId + "-" + actualHash.substring(0, Math.min(12, actualHash.length()));
        KnowledgeVersion version = createVersion(source, versionNo, title, "", uri.toString(), actualHash, username);
        version.setPublishedTime(parsePublishedTime(publishedAt)); mapper.updateVersion(version);
        KnowledgeBase actualSource = source;
        return submitSync(version, username, () -> processCollectedNews(actualSource, version, title, normalized,
            buildNewsEvidence(articleId, sourceName, sourceSite, publishedAt, crawledAt, uri.toString(), actualHash)
                .toJSONString()));
    }

    private JSONObject buildNewsEvidence(Long articleId, String sourceName, String sourceSite, String publishedAt,
        String crawledAt, String originalUrl, String actualHash)
    {
        JSONObject evidence = new JSONObject();
        evidence.put("kind", "NEWS");
        evidence.put("news_id", articleId);
        evidence.put("source_name", clean(sourceName));
        evidence.put("source_site", clean(sourceSite));
        evidence.put("published_at", clean(publishedAt));
        evidence.put("crawled_at", clean(crawledAt));
        evidence.put("original_url", originalUrl);
        evidence.put("content_hash", actualHash);
        return evidence;
    }

    /**
     * Publish one Dongchedi vehicle model into the unified knowledge tables.
     * Each model owns a stable REPORT source so updates create versions instead of overwriting peers.
     */
    public synchronized KnowledgeIngestTask submitCollectedVehicleModel(Long modelId, String brandName, String seriesName,
        String modelName, String manufacturer, String officialGuidePrice, String level, String energyType,
        String instrumentScreenSizeInch, String instrumentScreenStyle, String centerScreenSizeInch,
        String centerScreenMaterial, String passengerScreenSizeInch, String rearScreenSizeInch,
        String sourceUrl, String dongchediCarId, String dongchediSeriesId, String crawledAt, String username)
        throws Exception
    {
        if (modelId == null) throw new IllegalArgumentException("车型ID不能为空");
        String title = normalizeText(firstNonBlank(brandName, "") + " " + firstNonBlank(seriesName, "") + " "
            + firstNonBlank(modelName, "车型参数"));
        StringBuilder body = new StringBuilder();
        body.append("数据来源：懂车帝\n");
        body.append("品牌：").append(firstNonBlank(brandName, "--")).append('\n');
        body.append("车系：").append(firstNonBlank(seriesName, "--")).append('\n');
        body.append("车型：").append(firstNonBlank(modelName, "--")).append('\n');
        body.append("厂商：").append(firstNonBlank(manufacturer, "--")).append('\n');
        body.append("官方指导价：").append(firstNonBlank(officialGuidePrice, "--")).append('\n');
        body.append("级别：").append(firstNonBlank(level, "--")).append('\n');
        body.append("能源类型：").append(firstNonBlank(energyType, "--")).append('\n');
        body.append("仪表屏尺寸：").append(firstNonBlank(instrumentScreenSizeInch, "--")).append('\n');
        body.append("仪表屏样式：").append(firstNonBlank(instrumentScreenStyle, "--")).append('\n');
        body.append("中控屏尺寸：").append(firstNonBlank(centerScreenSizeInch, "--")).append('\n');
        body.append("中控屏材质：").append(firstNonBlank(centerScreenMaterial, "--")).append('\n');
        body.append("副驾屏尺寸：").append(firstNonBlank(passengerScreenSizeInch, "--")).append('\n');
        body.append("后排屏尺寸：").append(firstNonBlank(rearScreenSizeInch, "--")).append('\n');
        body.append("懂车帝车型ID：").append(firstNonBlank(dongchediCarId, "--")).append('\n');
        body.append("懂车帝车系ID：").append(firstNonBlank(dongchediSeriesId, "--"));
        String normalized = normalizeText(body.toString());
        if (normalized.length() < 20) throw new IllegalArgumentException("车型参数正文过短，无法入库");
        String fallbackUrl = "https://www.dongchedi.com/auto/series/"
            + firstNonBlank(dongchediSeriesId, String.valueOf(modelId));
        URI uri = validateExternalUri(firstNonBlank(sourceUrl, fallbackUrl));
        String actualHash = storage.sha256(normalized);
        String sourceCode = "VEHICLE-MODEL-" + modelId;
        KnowledgeBase source = mapper.selectKnowledgeBaseBySourceCode(sourceCode);
        if (source == null)
        {
            source = new KnowledgeBase(); source.setSourceCode(sourceCode);
            source.setSourceName(title); source.setSourceType("REPORT");
            source.setConfidentiality("INTERNAL"); source.setAllowedPurpose("知识问答、车型参数检索及来源追溯");
            source.setAllowedRoleIds(""); source.setEnabled("1"); source.setStatus("0"); source.setCreateBy(username);
            mapper.insertKnowledgeBase(source);
        }
        KnowledgeVersion existing = mapper.selectVersionByHash(source.getId(), actualHash);
        if (existing != null)
        {
            KnowledgeIngestTask task = mapper.selectIngestTaskByVersionId(existing.getId());
            if (task != null) return task;
        }
        String versionNo = "vehicle-" + modelId + "-" + actualHash.substring(0, Math.min(12, actualHash.length()));
        KnowledgeVersion version = createVersion(source, versionNo, title, "", uri.toString(), actualHash, username);
        version.setPublishedTime(parsePublishedTime(crawledAt)); mapper.updateVersion(version);
        JSONObject evidence = new JSONObject(); evidence.put("kind", "DONGCHEDI_VEHICLE");
        evidence.put("model_id", modelId); evidence.put("dongchedi_car_id", clean(dongchediCarId));
        evidence.put("dongchedi_series_id", clean(dongchediSeriesId)); evidence.put("brand_name", clean(brandName));
        evidence.put("series_name", clean(seriesName)); evidence.put("model_name", clean(modelName));
        evidence.put("crawled_at", clean(crawledAt)); evidence.put("original_url", uri.toString());
        evidence.put("content_hash", actualHash);
        KnowledgeBase actualSource = source;
        return submit(version, username, () -> processCollectedNews(actualSource, version, title, normalized, evidence.toJSONString()));
    }

    /** 导入爬虫输出的新闻JSON批次；每条新闻保留独立标题、时间、站点和原文URL。 */
    public KnowledgeIngestTask submitNewsJson(Long sourceId, String versionNo, MultipartFile file,
        String username) throws IOException
    {
        if (!newsIngestEnabled)
            throw new IllegalStateException("新闻入库接口已预留，当前POC未启用");
        KnowledgeBase source = requireSource(sourceId, "NEWS");
        String originalName = file.getOriginalFilename() == null ? "news.json"
            : Path.of(file.getOriginalFilename()).getFileName().toString();
        if (!originalName.toLowerCase(Locale.ROOT).endsWith(".json"))
            throw new IllegalArgumentException("批量新闻仅支持JSON文件");
        if (file.isEmpty()) throw new IllegalArgumentException("新闻JSON文件为空");
        String raw = new String(file.getBytes(), java.nio.charset.StandardCharsets.UTF_8);
        List<NewsArticle> articles = parseNewsArticles(raw);
        String hash = storage.sha256(raw);
        KnowledgeVersion version = createVersion(source, versionNo, originalName, "", "", hash, username);
        return submit(version, username, () -> processNewsBatch(source, version, raw, articles));
    }

    /** 入库已取得的政策原文；POC不主动抓取政府网站，保留发布机关、日期、级别和原文URL。 */
    public KnowledgeIngestTask submitPolicy(Long sourceId, String versionNo, String url, String title,
        String content, String issuedBy, String publishedAt, String policyLevel, String username) throws Exception
    {
        KnowledgeBase source = requireSource(sourceId, "POLICY");
        URI uri = validateExternalUri(url);
        String actualTitle = title == null || title.isBlank() ? source.getSourceName() : title.trim();
        String normalized = normalizeText(content);
        if (normalized.length() < 20) throw new IllegalArgumentException("政策正文至少20个字符");
        String metadata = "政策名称：" + actualTitle
            + (clean(issuedBy).isBlank() ? "" : "\n发布机关：" + clean(issuedBy))
            + (clean(publishedAt).isBlank() ? "" : "\n发布时间：" + clean(publishedAt))
            + (clean(policyLevel).isBlank() ? "" : "\n政策级别：" + clean(policyLevel))
            + "\n原文URL：" + uri + "\n政策正文：" + normalized;
        String hash = storage.sha256(metadata);
        KnowledgeVersion version = createVersion(source, versionNo, actualTitle, "", uri.toString(), hash, username);
        version.setPublishedTime(parsePublishedTime(publishedAt));
        mapper.updateVersion(version);
        JSONObject evidence = new JSONObject();
        evidence.put("kind", "POLICY"); evidence.put("title", actualTitle);
        evidence.put("issued_by", clean(issuedBy)); evidence.put("published_at", clean(publishedAt));
        evidence.put("policy_level", clean(policyLevel)); evidence.put("original_url", uri.toString());
        return submit(version, username, () -> processPolicy(source, version, actualTitle, metadata,
            evidence.toJSONString()));
    }

    public KnowledgeIngestTask submitReport(Long sourceId, String versionNo, Long reportId, String username)
    {
        KnowledgeBase source = requireSource(sourceId, "REPORT");
        AiReport report = reportService.selectAiReportById(reportId);
        return submitReport(source, versionNo, report, username, false);
    }

    /** 报告生成成功后自动建立独立知识源，避免不同导入任务的当前版本互相覆盖。 */
    public KnowledgeIngestTask submitGeneratedReport(Long reportId, String username)
    {
        AiReport report = reportService.selectAiReportById(reportId);
        requireCompletedReport(report);
        String sourceCode = "AUTO-REPORT-" + (report.getImportTaskId() == null ? "ID-" + report.getId()
            : "TASK-" + report.getImportTaskId());
        KnowledgeBase source = mapper.selectKnowledgeBaseBySourceCode(sourceCode);
        if (source == null)
        {
            source = new KnowledgeBase();
            source.setSourceCode(sourceCode); source.setSourceName(report.getTaskName()); source.setSourceType("REPORT");
            source.setConfidentiality("INTERNAL"); source.setAllowedPurpose("知识问答、报告分析及来源追溯");
            source.setAllowedRoleIds(""); source.setEnabled("1"); source.setStatus("0"); source.setCreateBy(username);
            mapper.insertKnowledgeBase(source);
        }
        return submitReport(source, "report-" + report.getId(), report, username, true);
    }

    /**
     * Automatically publish one market-analysis Office export as a generated-report
     * knowledge source.  The Office binary is retained for traceability while the
     * deterministic report JSON is chunked for search and Q&A.
     */
    public synchronized KnowledgeIngestTask submitGeneratedMarketReport(String datasetId, String format,
        String fileName, byte[] fileContent, String reportContent, String exportScope, String username) throws IOException
    {
        VehicleMarketPublishResult published = publishVehicleMarketReport(datasetId, reportContent, username,
            fileName, fileContent, format, exportScope);
        return published.knowledgeTask();
    }

    /**
     * Align vehicle weekly reports with display-analysis publishing: write {@code business_report}
     * first, then ingest searchable chunks through the same generated-report knowledge path.
     * Optional Office bytes are attached to the knowledge version for durable download.
     */
    public synchronized VehicleMarketPublishResult publishVehicleMarketReport(String datasetId, String reportContent,
        String username, String officeFileName, byte[] officeBytes, String format, String scope) throws IOException
    {
        String actualDatasetId = clean(datasetId);
        if (actualDatasetId.isBlank()) throw new IllegalArgumentException("整车分析数据集不能为空");
        JSONObject parsed;
        try { parsed = JSON.parseObject(reportContent); }
        catch (Exception ex) { throw new IllegalArgumentException("整车市场报告不是有效JSON", ex); }
        if (parsed == null || parsed.isEmpty()) throw new IllegalArgumentException("整车市场报告内容为空");
        String canonicalReport = JSON.toJSONString(parsed);

        String taskName = "整车市场周报 - " + actualDatasetId;
        AiReport report = reportService.selectLatestAiReportByTaskName(taskName);
        boolean created = false;
        if (report == null)
        {
            report = new AiReport();
            report.setTaskName(taskName);
            report.setReportType("vehicle_market_v21");
            report.setGenerationMode("market_agent_report");
            report.setStatus("2");
            report.setReportContent(canonicalReport);
            report.setCreateBy(username);
            report.setRemark(buildVehicleRemark(actualDatasetId, scope, format));
            reportService.insertAiReport(report);
            created = true;
        }
        else
        {
            report.setReportContent(canonicalReport);
            report.setStatus("2");
            report.setUpdateBy(username);
            report.setRemark(buildVehicleRemark(actualDatasetId, scope, format));
            reportService.updateAiReport(report);
        }

        String sourceCode = "AUTO-REPORT-VEHICLE-"
            + storage.sha256(actualDatasetId).substring(0, 20).toUpperCase(Locale.ROOT);
        KnowledgeBase source = mapper.selectKnowledgeBaseBySourceCode(sourceCode);
        if (source == null)
        {
            source = new KnowledgeBase();
            source.setSourceCode(sourceCode); source.setSourceName(taskName); source.setSourceType("REPORT");
            source.setConfidentiality("INTERNAL"); source.setAllowedPurpose("知识问答、报告分析及来源追溯");
            source.setAllowedRoleIds(""); source.setEnabled("1"); source.setStatus("0"); source.setCreateBy(username);
            source.setRemark("整车市场分析周报自动入库");
            mapper.insertKnowledgeBase(source);
        }
        else
        {
            source.setSourceName(taskName); source.setUpdateBy(username); mapper.updateKnowledgeBase(source);
        }

        String hash = storage.sha256(canonicalReport);
        KnowledgeVersion existing = mapper.selectVersionByHash(source.getId(), hash);
        if (existing != null)
        {
            KnowledgeIngestTask task = mapper.selectIngestTaskByVersionId(existing.getId());
            if (task == null) throw new IllegalStateException("该生成报告版本已存在但缺少入库任务");
            attachOfficeToVersion(existing, officeFileName, officeBytes, format);
            return new VehicleMarketPublishResult(report, task, created);
        }

        KnowledgeVersion version = createVersion(source, "report-" + report.getId(), taskName, "", "", hash, username);
        attachOfficeToVersion(version, officeFileName, officeBytes, format);
        AiReport published = report;
        KnowledgeBase publishedSource = source;
        KnowledgeIngestTask knowledgeTask = submit(version, username,
            () -> processReport(publishedSource, version, published));
        return new VehicleMarketPublishResult(report, knowledgeTask, created);
    }

    private String buildVehicleRemark(String datasetId, String scope, String format)
    {
        StringBuilder remark = new StringBuilder("datasetId=").append(datasetId);
        if (format != null && !format.isBlank()) remark.append("|format=").append(format.trim().toLowerCase(Locale.ROOT));
        if (scope != null && !scope.isBlank()) remark.append("|scope=").append(scope.trim());
        return remark.length() > 500 ? remark.substring(0, 500) : remark.toString();
    }

    private void attachOfficeToVersion(KnowledgeVersion version, String officeFileName,
        byte[] officeBytes, String format) throws IOException
    {
        if (version == null || version.getId() == null) return;
        if (officeBytes == null || officeBytes.length == 0) return;
        String actualFormat = clean(format).toLowerCase(Locale.ROOT);
        if (!Set.of("xlsx", "docx", "pptx").contains(actualFormat)) return;
        String safeName = sanitizeOriginalName(officeFileName);
        if (safeName.isBlank()) safeName = "vehicle-market-report." + actualFormat;
        if (!safeName.toLowerCase(Locale.ROOT).endsWith("." + actualFormat))
            safeName = safeName + "." + actualFormat;
        KnowledgeFileStorage.StoredFile stored = storage.saveGeneratedReport(officeBytes, safeName);
        version.setOriginalName(stored.originalName());
        version.setStoredPath(stored.path().toString());
        version.setSourceUrl("/business/knowledge/versions/" + version.getId() + "/file");
        mapper.updateVersion(version);
    }

    public record VehicleMarketPublishResult(AiReport report, KnowledgeIngestTask knowledgeTask, boolean created) { }

    private KnowledgeIngestTask submitReport(KnowledgeBase source, String versionNo, AiReport report,
        String username, boolean idempotent)
    {
        requireCompletedReport(report);
        String hash = storage.sha256(report.getReportContent());
        KnowledgeVersion existing = mapper.selectVersionByHash(source.getId(), hash);
        if (existing != null && idempotent)
        {
            KnowledgeIngestTask task = mapper.selectIngestTaskByVersionId(existing.getId());
            if (task != null) return task;
        }
        if (existing != null) throw new IllegalArgumentException("该内容已入库，版本：" + existing.getVersionNo());
        KnowledgeVersion version = createVersion(source, versionNo, report.getTaskName(), "", "", hash, username);
        return submit(version, username, () -> processReport(source, version, report));
    }

    private void requireCompletedReport(AiReport report)
    {
        if (report == null || report.getReportContent() == null || report.getReportContent().isBlank())
            throw new IllegalArgumentException("结构化报告不存在或内容为空");
        if (report.getStatus() != null && !"2".equals(report.getStatus()))
            throw new IllegalArgumentException("仅允许入库生成成功的报告");
    }

    public KnowledgeIngestTask getTask(Long id) { return mapper.selectIngestTaskById(id); }

    public List<KnowledgeVersion> getVersions(Long sourceId) { return mapper.selectVersionsBySourceId(sourceId); }

    public List<KnowledgeChunk> search(String query, String sourceType, List<Long> roleIds, boolean admin, int limit)
    {
        return search(query, sourceType, roleIds, admin, limit, null, null, null);
    }

    public List<KnowledgeChunk> search(String query, String sourceType, List<Long> roleIds, boolean admin, int limit,
        Long sourceId, Long versionId)
    {
        return search(query, sourceType, roleIds, admin, limit, sourceId, versionId, null);
    }

    /**
     * 在可选的文档范围内检索。指定 sourceId/versionId 时：先定文档再取 topK，
     * 且不要求切片正文重复写出主体名（主体可来自文档元数据）。
     * extraEntityTerms 来自 LLM/规则主体解析，用于通用主体约束，不依赖写死品牌表。
     */
    public List<KnowledgeChunk> search(String query, String sourceType, List<Long> roleIds, boolean admin, int limit,
        Long sourceId, Long versionId, List<String> extraEntityTerms)
    {
        if (query == null || query.trim().length() < 2) throw new IllegalArgumentException("检索词至少2个字符");
        String actualQuery = query.trim();
        LinkedHashSet<String> merged = new LinkedHashSet<>(KnowledgeTextProcessor.detectEntityTerms(actualQuery));
        if (extraEntityTerms != null)
            for (String term : extraEntityTerms)
                if (term != null && !term.isBlank()) merged.add(term.trim());
        List<String> entityTerms = new ArrayList<>(merged);
        boolean scoped = sourceId != null || versionId != null;
        // 宽召回且已识别主体时，强制切片/文件名命中主体，避免大年报抢走其他主体问题
        boolean requireEntity = !scoped && !entityTerms.isEmpty();
        List<KnowledgeChunk> chunks = mapper.searchChunks(actualQuery, sourceType, roleIds, admin,
            Math.max(1, Math.min(limit, 50)), entityTerms, KnowledgeTextProcessor.normalizedLiteral(actualQuery),
            sourceId, versionId, requireEntity);
        for (KnowledgeChunk chunk : chunks)
        {
            String cleaned = KnowledgeTextProcessor.cleanPdfText(chunk.getContent());
            if (!cleaned.isBlank()) chunk.setContent(cleaned);
            chunk.setSourceSnippet(KnowledgeTextProcessor.buildSnippet(chunk.getContent(), actualQuery, entityTerms, 500));
        }
        return chunks;
    }

    /** 按名称关键字解析授权范围内的知识源（不写死 ID）。 */
    public KnowledgeBase resolveAuthorizedSourceByNameHint(String nameHint, String preferredType,
        List<Long> roleIds, boolean admin)
    {
        if (nameHint == null || nameHint.isBlank()) return null;
        KnowledgeBase filter = new KnowledgeBase();
        filter.setSourceName(nameHint.trim());
        if (preferredType != null && !preferredType.isBlank()) filter.setSourceType(preferredType.trim());
        List<KnowledgeBase> rows = mapper.selectAuthorizedKnowledgeBaseList(filter, roleIds, admin);
        if (rows == null || rows.isEmpty()) return null;
        for (KnowledgeBase row : rows)
        {
            if (row != null && "1".equals(row.getEnabled()) && row.getCurrentVersionId() != null)
                return row;
        }
        return rows.get(0);
    }

    public KnowledgeBase getAuthorizedSource(Long sourceId, List<Long> roleIds, boolean admin)
    {
        if (sourceId == null) return null;
        return mapper.selectAuthorizedKnowledgeBaseById(sourceId, roleIds, admin);
    }

    /** 为表格/章节问答补充同版本相邻切片（前后各 radius 条）。 */
    public List<KnowledgeChunk> expandAdjacentChunks(List<KnowledgeChunk> chunks, int radius)
    {
        if (chunks == null || chunks.isEmpty() || radius <= 0) return chunks == null ? List.of() : chunks;
        Map<String, KnowledgeChunk> merged = new LinkedHashMap<>();
        for (KnowledgeChunk chunk : chunks)
        {
            if (chunk == null) continue;
            String key = evidenceKey(chunk);
            merged.putIfAbsent(key, chunk);
            if (chunk.getVersionId() == null || chunk.getChunkNo() == null) continue;
            List<KnowledgeChunk> siblings = mapper.selectChunksByVersionId(chunk.getVersionId());
            if (siblings == null || siblings.isEmpty()) continue;
            int index = -1;
            for (int i = 0; i < siblings.size(); i++)
            {
                KnowledgeChunk sibling = siblings.get(i);
                if (sibling != null && chunk.getChunkNo().equals(sibling.getChunkNo()))
                {
                    index = i;
                    break;
                }
            }
            if (index < 0) continue;
            for (int offset = 1; offset <= radius; offset++)
            {
                if (index - offset >= 0) merged.putIfAbsent(evidenceKey(siblings.get(index - offset)), siblings.get(index - offset));
                if (index + offset < siblings.size())
                    merged.putIfAbsent(evidenceKey(siblings.get(index + offset)), siblings.get(index + offset));
            }
        }
        return new ArrayList<>(merged.values());
    }

    /** 取同版本的指定序号切片，供补全被切在边界上的句子。 */
    public KnowledgeChunk findChunk(Long versionId, Integer chunkNo)
    {
        if (versionId == null || chunkNo == null || chunkNo < 1) return null;
        KnowledgeChunk chunk = mapper.selectChunkByVersionAndNo(versionId, chunkNo);
        if (chunk == null || chunk.getContent() == null) return chunk;
        String cleaned = KnowledgeTextProcessor.cleanPdfText(chunk.getContent());
        if (!cleaned.isBlank()) chunk.setContent(cleaned);
        return chunk;
    }

    private static String evidenceKey(KnowledgeChunk chunk)
    {
        if (chunk == null) return "null";
        if (chunk.getId() != null) return "ID:" + chunk.getId();
        return String.valueOf(chunk.getSourceId()) + ":" + chunk.getVersionId() + ":" + chunk.getChunkNo();
    }

    /**
     * 将固定知识库当前有效版本正文导出为行业资料文本（整车市场分析「从知识库添加」）。
     */
    public Map<String, Object> exportSourceTextForContext(Long sourceId, String expectedType,
        List<Long> roleIds, boolean admin)
    {
        if (sourceId == null) throw new IllegalArgumentException("请选择知识库资料");
        KnowledgeBase source = mapper.selectAuthorizedKnowledgeBaseById(sourceId, roleIds, admin);
        if (source == null) throw new IllegalArgumentException("知识库资料不存在或无权限");
        if (!"1".equals(source.getEnabled())) throw new IllegalArgumentException("知识源未启用：" + source.getSourceName());
        if (source.getCurrentVersionId() == null) throw new IllegalArgumentException("知识源尚未入库：" + source.getSourceName());
        String sourceType = source.getSourceType() == null ? "" : source.getSourceType().trim().toUpperCase(Locale.ROOT);
        String expected = expectedType == null ? "" : expectedType.trim().toUpperCase(Locale.ROOT);
        if (!expected.isEmpty() && !expected.equals(sourceType))
            throw new IllegalArgumentException("资料「" + source.getSourceName() + "」分类为" + sourceType + "，与所选类别" + expected + "不一致");
        KnowledgeVersion version = mapper.selectVersionById(source.getCurrentVersionId());
        if (version == null || !"2".equals(version.getStatus()))
            throw new IllegalArgumentException("知识源当前版本不可用：" + source.getSourceName());
        List<KnowledgeChunk> chunks = mapper.selectChunksByVersionId(source.getCurrentVersionId());
        if (chunks == null || chunks.isEmpty())
            throw new IllegalArgumentException("知识源无可用正文切片：" + source.getSourceName());
        StringBuilder text = new StringBuilder();
        for (KnowledgeChunk chunk : chunks)
        {
            if (chunk.getTitlePath() != null && !chunk.getTitlePath().isBlank())
                text.append(chunk.getTitlePath().trim()).append('\n');
            if (chunk.getContent() != null && !chunk.getContent().isBlank())
                text.append(chunk.getContent().trim()).append("\n\n");
        }
        String content = text.toString().trim();
        if (content.length() < 8) throw new IllegalArgumentException("知识源正文过短：" + source.getSourceName());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceId", source.getId());
        result.put("sourceName", source.getSourceName());
        result.put("sourceType", sourceType);
        result.put("versionId", version.getId());
        result.put("versionNo", version.getVersionNo());
        result.put("locator", "知识库#" + source.getId() + "/v" + version.getId());
        result.put("content", content);
        return result;
    }

    /** 行业资料选择器：列出已启用且已入库的固定知识库资料。 */
    public List<Map<String, Object>> listEnabledSourcesForContext(String sourceType, String sourceName,
        List<Long> roleIds, boolean admin)
    {
        KnowledgeBase filter = new KnowledgeBase();
        filter.setEnabled("1");
        if (sourceType != null && !sourceType.isBlank()) filter.setSourceType(sourceType.trim().toUpperCase(Locale.ROOT));
        if (sourceName != null && !sourceName.isBlank()) filter.setSourceName(sourceName.trim());
        List<KnowledgeBase> sources = mapper.selectAuthorizedKnowledgeBaseList(filter,
            roleIds == null ? List.of() : roleIds, admin);
        List<Map<String, Object>> rows = new ArrayList<>();
        for (KnowledgeBase source : sources)
        {
            if (source.getCurrentVersionId() == null) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", source.getId());
            row.put("sourceCode", source.getSourceCode());
            row.put("sourceName", source.getSourceName());
            row.put("sourceType", source.getSourceType());
            row.put("currentVersionId", source.getCurrentVersionId());
            row.put("enabled", source.getEnabled());
            row.put("status", source.getStatus());
            rows.add(row);
        }
        return rows;
    }

    /** 数值型问题优先从结构化报告投影出的metric_id切片中命中确定性指标。 */
    public List<KnowledgeChunk> searchMetrics(String query, List<Long> roleIds, boolean admin, int limit)
    {
        if (!metricQueryRouter.isMetricQuestion(query)) return List.of();
        List<KnowledgeChunk> candidates = mapper.selectCurrentMetricChunks(
            roleIds == null ? List.of() : roleIds, admin, metricQueryRouter.candidateTerms(query), 1000);
        List<KnowledgeChunk> ranked = metricQueryRouter.rank(query, metricQueryRouter.coalesceFragments(candidates),
            Math.max(1, Math.min(limit, 20)));
        List<KnowledgeChunk> hydrated = new ArrayList<>();
        for (KnowledgeChunk selected : ranked)
        {
            List<KnowledgeChunk> fragments = mapper.selectMetricFragments(selected.getVersionId(), selected.getMetricId(),
                selected.getTitlePath());
            List<KnowledgeChunk> merged = metricQueryRouter.coalesceFragments(fragments);
            KnowledgeChunk complete = merged.isEmpty() ? selected : merged.get(0);
            complete.setScore(selected.getScore());
            hydrated.add(complete);
        }
        List<String> entityTerms = KnowledgeTextProcessor.detectEntityTerms(query);
        for (KnowledgeChunk chunk : hydrated)
            chunk.setSourceSnippet(KnowledgeTextProcessor.buildSnippet(chunk.getContent(), query, entityTerms, 500));
        return hydrated;
    }

    /** 把按 1200 字拆开的周报指标拼回，月度销量序列才不会和品牌名分成两段。 */
    public List<KnowledgeChunk> expandMetricFragments(List<KnowledgeChunk> chunks)
    {
        if (chunks == null || chunks.isEmpty()) return List.of();
        List<KnowledgeChunk> expanded = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (KnowledgeChunk chunk : chunks)
        {
            if (chunk == null) continue;
            String metricId = chunk.getMetricId() == null ? "" : chunk.getMetricId();
            if (metricId.isBlank() || chunk.getVersionId() == null)
            {
                expanded.add(chunk);
                continue;
            }
            String key = chunk.getVersionId() + ":" + metricId + ":" + (chunk.getTitlePath() == null ? "" : chunk.getTitlePath());
            if (!seen.add(key)) continue;
            List<KnowledgeChunk> fragments = mapper.selectMetricFragments(chunk.getVersionId(), metricId, chunk.getTitlePath());
            List<KnowledgeChunk> merged = metricQueryRouter.coalesceFragments(
                fragments == null || fragments.isEmpty() ? List.of(chunk) : fragments);
            expanded.add(merged.isEmpty() ? chunk : merged.get(0));
        }
        return expanded;
    }

    /**
     * 点名车企问销量时：检索常先命中 market.meta 溯源碎片。
     * 这里把同版本周报里的 fact_pack / 折线 / 洞察一并拉回，才能用上 monthly_trend 等 JSON。
     */
    public List<KnowledgeChunk> expandVehicleSalesReportContext(List<KnowledgeChunk> chunks)
    {
        if (chunks == null || chunks.isEmpty()) return List.of();
        List<KnowledgeChunk> expanded = new ArrayList<>(expandMetricFragments(chunks));
        Set<String> seen = new LinkedHashSet<>();
        for (KnowledgeChunk chunk : expanded)
        {
            if (chunk == null || chunk.getId() == null) continue;
            seen.add("ID:" + chunk.getId());
        }
        Set<Long> versionIds = new LinkedHashSet<>();
        for (KnowledgeChunk chunk : expanded)
        {
            if (chunk == null || chunk.getVersionId() == null) continue;
            String metricId = chunk.getMetricId() == null ? "" : chunk.getMetricId();
            if (metricId.startsWith("market.") || "REPORT".equalsIgnoreCase(safe(chunk.getSourceType())))
                versionIds.add(chunk.getVersionId());
        }
        for (Long versionId : versionIds)
        {
            List<KnowledgeChunk> siblings = mapper.selectChunksByVersionId(versionId);
            if (siblings == null || siblings.isEmpty()) continue;
            List<KnowledgeChunk> useful = new ArrayList<>();
            for (KnowledgeChunk sibling : siblings)
            {
                if (sibling == null) continue;
                String metricId = sibling.getMetricId() == null ? "" : sibling.getMetricId().toLowerCase();
                String content = sibling.getContent() == null ? "" : sibling.getContent();
                boolean usefulMetric = metricId.equals("market.market_fact_pack")
                    || metricId.contains("fact_pack")
                    || metricId.equals("market.top_model")
                    || metricId.equals("market.full_model")
                    || metricId.contains("line_chart");
                boolean usefulJson = content.contains("\"monthly_trend\"")
                    || (content.contains("\"对象\"") && content.contains("销量/数值"));
                if (!usefulMetric && !usefulJson) continue;
                useful.add(sibling);
            }
            for (KnowledgeChunk merged : metricQueryRouter.coalesceFragments(useful))
            {
                if (merged == null) continue;
                String key = merged.getId() == null
                    ? versionId + ":" + safe(merged.getMetricId()) + ":" + safe(merged.getTitlePath())
                    : "ID:" + merged.getId();
                if (!seen.add(key)) continue;
                expanded.add(merged);
            }
        }
        return expanded;
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private KnowledgeIngestTask submit(KnowledgeVersion version, String username, ThrowingRunnable processor)
    {
        KnowledgeIngestTask task = new KnowledgeIngestTask();
        task.setSourceId(version.getSourceId()); task.setVersionId(version.getId()); task.setStatus("0");
        task.setProgress(0); task.setCurrentStage("排队中"); task.setChunkCount(0); task.setErrorMessage(""); task.setCreateBy(username);
        mapper.insertIngestTask(task);
        try
        {
            executor.execute(() -> runTask(task.getId(), version.getId(), processor));
        }
        catch (RuntimeException e)
        {
            fail(task, version, "知识库入库队列已满，请稍后重试");
            throw new IllegalStateException("知识库入库队列已满，请稍后重试");
        }
        return task;
    }

    /** Collected-news publish waits until MySQL knowledge rows are ready for QA. */
    private KnowledgeIngestTask submitSync(KnowledgeVersion version, String username, ThrowingRunnable processor)
    {
        KnowledgeIngestTask task = mapper.selectIngestTaskByVersionId(version.getId());
        if (task == null)
        {
            task = new KnowledgeIngestTask();
            task.setSourceId(version.getSourceId()); task.setVersionId(version.getId()); task.setStatus("0");
            task.setProgress(0); task.setCurrentStage("排队中"); task.setChunkCount(0); task.setErrorMessage("");
            task.setCreateBy(username);
            mapper.insertIngestTask(task);
        }
        runTask(task.getId(), version.getId(), processor);
        KnowledgeIngestTask finished = mapper.selectIngestTaskById(task.getId());
        if (finished == null || !"2".equals(finished.getStatus()))
            throw new IllegalStateException(finished == null || finished.getErrorMessage() == null
                || finished.getErrorMessage().isBlank() ? "新闻入库失败" : finished.getErrorMessage());
        return finished;
    }

    private void runTask(Long taskId, Long versionId, ThrowingRunnable processor)
    {
        KnowledgeIngestTask task = mapper.selectIngestTaskById(taskId);
        KnowledgeVersion version = mapper.selectVersionById(versionId);
        try
        {
            task.setStatus("1"); task.setProgress(10); task.setCurrentStage("解析与切分"); task.setStartedTime(LocalDateTime.now());
            mapper.updateIngestTask(task);
            version.setStatus("1"); mapper.updateVersion(version);
            processor.run();
            task = mapper.selectIngestTaskById(taskId);
            version = mapper.selectVersionById(versionId);
            task.setStatus("2"); task.setProgress(100); task.setCurrentStage("入库完成"); task.setFinishedTime(LocalDateTime.now());
            mapper.updateIngestTask(task);
            version.setStatus("2"); mapper.updateVersion(version);
            // 版本ID按提交顺序递增；原子条件更新防止较早任务后完成时覆盖较新成功版本。
            mapper.promoteCurrentVersionIfNewer(version.getSourceId(), version.getId(), "当前版本：" + version.getVersionNo());
        }
        catch (Exception e)
        {
            fail(task, version, e.getMessage() == null ? "知识库入库失败" : e.getMessage());
        }
    }

    private void processPdf(KnowledgeBase source, KnowledgeVersion version) throws IOException
    {
        if (agentDocumentEnabled)
        {
            processAgentDocument(source, version);
            return;
        }
        clearGraph(version.getId());
        mapper.deleteChunksByVersionId(version.getId());
        int number = 0;
        List<Integer> imageOnlyPages = new ArrayList<>();
        try (PDDocument document = Loader.loadPDF(Path.of(version.getStoredPath()).toFile()))
        {
            PDFTextStripper stripper = new PDFTextStripper();
            // 按视觉坐标排序并保留更多换行，降低表格文本被串成无意义数字流的概率。
            stripper.setSortByPosition(true);
            stripper.setAddMoreFormatting(true);
            version.setPageCount(document.getNumberOfPages());
            for (int page = 1; page <= document.getNumberOfPages(); page++)
            {
                stripper.setStartPage(page); stripper.setEndPage(page);
                String text = KnowledgeTextProcessor.cleanPdfText(stripper.getText(document));
                if (text.isBlank()) imageOnlyPages.add(page);
                number = insertTextChunks(source, version, source.getSourceName(), text, page, page, null, null, null, number);
                updateProgress(version.getId(), number, 10 + (int)(75.0 * page / Math.max(1, document.getNumberOfPages())));
            }
        }
        if (number == 0) throw new IOException("PDF未提取到文字，可能是扫描版文件，需要OCR");
        if (!imageOnlyPages.isEmpty())
            version.setErrorMessage("解析警告：以下页面没有可检索文本层，需要OCR/图片解析：" + imageOnlyPages);
        completeChunks(version, number);
    }

    private void processAgentDocument(KnowledgeBase source, KnowledgeVersion version) throws IOException
    {
        if (agentServiceClient == null) throw new IOException("Python文档解析服务未配置");
        JSONObject result;
        try
        {
            String lower = version.getOriginalName() == null ? "" : version.getOriginalName().toLowerCase(Locale.ROOT);
            String contentType = lower.endsWith(".pptx")
                ? "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                : lower.endsWith(".txt") ? "text/plain" : "application/pdf";
            result = agentServiceClient.parseDocument(Path.of(version.getStoredPath()), version.getOriginalName(), contentType);
        }
        catch (AgentServiceClientException e)
        {
            throw new IOException("Python文档解析失败：" + e.getMessage(), e);
        }
        processAgentResult(source, version, result);
    }

    private void processAgentResult(KnowledgeBase source, KnowledgeVersion version, JSONObject result) throws IOException
    {
        if (!"success".equalsIgnoreCase(result.getString("status")) || !result.getBooleanValue("supported"))
            throw new IOException("文档解析失败：" + firstNonBlank(result.getString("message"), result.getString("reason"), "未知错误"));
        if (result.getBooleanValue("indexed") || result.getBooleanValue("index_to_kb")
            || result.getBooleanValue("persist_review") || result.getIntValue("review_count") > 0)
            throw new IOException("解析服务违反统一入库约束，已拒绝结果");

        clearGraph(version.getId());
        mapper.deleteFactsByVersionId(version.getId());
        mapper.deleteChunksByVersionId(version.getId());
        JSONArray chunks = result.getJSONArray("semantic_chunks");
        if (chunks == null || chunks.isEmpty()) chunks = result.getJSONArray("chunk_data");
        int number = 0;
        if (chunks != null)
        {
            for (int index = 0; index < chunks.size(); index++)
            {
                JSONObject item = chunks.getJSONObject(index);
                if (item == null) continue;
                JSONObject metadata = item.getJSONObject("metadata");
                if (metadata != null && metadata.containsKey("indexable")
                    && !metadata.getBooleanValue("indexable")) continue;
                JSONObject locator = item.getJSONObject("source");
                Integer start = locator == null ? null : firstInteger(locator, "page_start", "slide_start", "page", "slide");
                Integer end = locator == null ? null : firstInteger(locator, "page_end", "slide_end", "page", "slide");
                JSONObject evidence = new JSONObject();
                evidence.put("kind", "PARSED_DOCUMENT"); evidence.put("chunk_type", item.getString("chunk_type"));
                evidence.put("source", locator); evidence.put("metadata", metadata);
                number = insertTextChunks(source, version,
                    firstNonBlank(item.getString("title"), version.getOriginalName()), item.getString("content"),
                    start, end, null, null, evidence.toJSONString(), number);
            }
        }
        if (number == 0) throw new IOException("解析结果没有可入库的语义切片");
        JSONObject validation = result.getJSONObject("validation");
        if (validation != null)
        {
            JSONObject validationMetadata = validation.getJSONObject("metadata");
            if (validationMetadata != null && validationMetadata.getInteger("pages") != null)
                version.setPageCount(validationMetadata.getInteger("pages"));
        }
        JSONObject parseSummary = new JSONObject();
        parseSummary.put("validation", validation);
        parseSummary.put("warnings", result.getJSONArray("warning"));
        parseSummary.put("semantic_chunk_stats", result.getJSONObject("semantic_chunk_stats"));
        version.setErrorMessage("解析校验：" + truncate(parseSummary.toJSONString(), 900));
        completeChunks(version, number);
    }

    private String sanitizeOriginalName(String value)
    {
        return value == null || value.isBlank() ? "document.pdf" : Path.of(value).getFileName().toString();
    }

    private String stringName(Path value)
    {
        return value == null || value.getFileName() == null ? "文档解析任务" : value.getFileName().toString();
    }

    private Integer firstInteger(JSONObject value, String... keys)
    {
        for (String key : keys) if (value.containsKey(key) && value.getInteger(key) != null) return value.getInteger(key);
        return null;
    }

    private String truncate(String value, int maximum)
    {
        return value == null || value.length() <= maximum ? value : value.substring(0, maximum);
    }

    private void processText(KnowledgeBase source, KnowledgeVersion version, String title, String text, Long reportId) throws IOException
    {
        clearGraph(version.getId());
        mapper.deleteChunksByVersionId(version.getId());
        int count = insertTextChunks(source, version, title, text, null, null, version.getSourceUrl(), reportId, null, 0);
        if (count == 0) throw new IOException("没有可入库的正文");
        completeChunks(version, count);
    }

    private void processNews(KnowledgeBase source, KnowledgeVersion version, String title, URI uri) throws Exception
    {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20))
            .header("User-Agent", "RuoYi-Knowledge-POC/1.0").GET().build();
        HttpResponse<String> response = httpClient.send(request,
            HttpResponse.BodyHandlers.ofString(java.nio.charset.StandardCharsets.UTF_8));
        if (response.statusCode() < 200 || response.statusCode() >= 300)
            throw new IOException("新闻抓取失败，HTTP " + response.statusCode());
        String text = htmlToText(response.body());
        if (text.length() < 50) throw new IOException("新闻正文过短，无法入库");
        String contentHash = storage.sha256(text);
        KnowledgeVersion duplicate = mapper.selectVersionByHash(source.getId(), contentHash);
        if (duplicate != null && !duplicate.getId().equals(version.getId()))
            throw new IOException("新闻内容已入库，版本：" + duplicate.getVersionNo());
        Path snapshot = storage.saveNewsSnapshot(text, contentHash);
        version.setStoredPath(snapshot.toString()); version.setContentSha256(contentHash);
        version.setFetchedTime(LocalDateTime.now()); mapper.updateVersion(version);
        processText(source, version, title, text, null);
    }

    private void processNewsText(KnowledgeBase source, KnowledgeVersion version, String title, String text) throws IOException
    {
        Path snapshot = storage.saveNewsSnapshot(text, version.getContentSha256());
        version.setStoredPath(snapshot.toString()); version.setFetchedTime(LocalDateTime.now()); mapper.updateVersion(version);
        processText(source, version, title, text, null);
    }

    private void processCollectedNews(KnowledgeBase source, KnowledgeVersion version, String title, String text,
        String evidenceJson) throws IOException
    {
        Path snapshot = storage.saveNewsSnapshot(text, version.getContentSha256());
        version.setStoredPath(snapshot.toString()); version.setFetchedTime(LocalDateTime.now()); mapper.updateVersion(version);
        clearGraph(version.getId()); mapper.deleteChunksByVersionId(version.getId());
        int count = insertTextChunks(source, version, title, text, null, null, version.getSourceUrl(), null,
            evidenceJson, "", 0);
        if (count == 0) throw new IOException("新闻没有可入库正文");
        completeChunks(version, count);
    }

    private void processPolicy(KnowledgeBase source, KnowledgeVersion version, String title, String text,
        String evidenceJson) throws IOException
    {
        Path snapshot = storage.savePolicySnapshot(text, version.getContentSha256());
        version.setStoredPath(snapshot.toString()); version.setFetchedTime(LocalDateTime.now()); mapper.updateVersion(version);
        clearGraph(version.getId()); mapper.deleteChunksByVersionId(version.getId());
        int count = insertTextChunks(source, version, title, text, null, null, version.getSourceUrl(), null,
            evidenceJson, "", 0);
        if (count == 0) throw new IOException("政策没有可入库正文");
        completeChunks(version, count);
    }

    private void processNewsBatch(KnowledgeBase source, KnowledgeVersion version, String raw,
        List<NewsArticle> articles) throws IOException
    {
        Path snapshot = storage.saveNewsSnapshot(raw, version.getContentSha256());
        version.setStoredPath(snapshot.toString()); version.setFetchedTime(LocalDateTime.now()); mapper.updateVersion(version);
        clearGraph(version.getId()); mapper.deleteChunksByVersionId(version.getId());
        int count = 0;
        for (NewsArticle article : articles)
        {
            JSONObject evidence = new JSONObject();
            evidence.put("kind", "NEWS"); evidence.put("news_id", article.id());
            evidence.put("source_name", article.sourceName()); evidence.put("source_site", article.sourceSite());
            evidence.put("published_at", article.publishedAt()); evidence.put("crawled_at", article.crawledAt());
            evidence.put("original_url", article.url()); evidence.put("content_hash", article.contentHash());
            String text = "标题：" + article.title() + "\n来源：" + article.sourceName()
                + (article.publishedAt().isBlank() ? "" : "\n发布时间：" + article.publishedAt())
                + "\n正文：" + article.content();
            count = insertTextChunks(source, version, article.sourceName() + " / " + article.title(), text,
                null, null, article.url(), null, evidence.toJSONString(), "", count);
        }
        if (count == 0) throw new IOException("新闻JSON没有可入库正文");
        completeChunks(version, count);
    }

    private List<NewsArticle> parseNewsArticles(String raw)
    {
        Object root;
        try { root = JSON.parse(raw); }
        catch (Exception ex) { throw new IllegalArgumentException("新闻JSON格式无效", ex); }
        com.alibaba.fastjson2.JSONArray items = root instanceof com.alibaba.fastjson2.JSONArray array ? array
            : root instanceof JSONObject object ? object.getJSONArray("items") : null;
        if (items == null || items.isEmpty()) throw new IllegalArgumentException("新闻JSON缺少非空items数组");
        if (items.size() > 1000) throw new IllegalArgumentException("单次最多导入1000条新闻");
        Map<String, NewsArticle> unique = new LinkedHashMap<>();
        for (int i = 0; i < items.size(); i++)
        {
            JSONObject item = items.getJSONObject(i);
            if (item == null) throw new IllegalArgumentException("第" + (i + 1) + "条新闻不是JSON对象");
            String title = clean(item.getString("title")); String content = clean(item.getString("content"));
            String url = firstNonBlank(item.getString("canonical_url"), item.getString("url"), item.getString("original_url"));
            if (title.isBlank() || content.length() < 20 || url.isBlank())
                throw new IllegalArgumentException("第" + (i + 1) + "条新闻缺少标题、有效正文或URL");
            validateExternalUri(url);
            String contentHash = firstNonBlank(item.getString("content_hash"), storage.sha256(content));
            NewsArticle article = new NewsArticle(item.getString("id"),
                firstNonBlank(item.getString("source_name"), item.getString("source_site"), "未知来源"),
                clean(item.getString("source_site")), title, content, clean(item.getString("published_at")),
                clean(item.getString("crawled_at")), url, contentHash);
            unique.putIfAbsent(contentHash, article);
        }
        return new ArrayList<>(unique.values());
    }

    private String firstNonBlank(String... values)
    {
        for (String value : values) if (value != null && !value.isBlank()) return value.trim();
        return "";
    }

    private String clean(String value) { return value == null ? "" : value.trim(); }

    private void processReport(KnowledgeBase source, KnowledgeVersion version, AiReport report) throws IOException
    {
        clearGraph(version.getId());
        mapper.deleteChunksByVersionId(version.getId());
        int count = 0;
        if ("vehicle_market_v21".equals(report.getReportType()))
        {
            count = processVehicleMarketReportChunks(source, version, report);
        }
        else
        {
            KnowledgeReportProjectionService projection = reportProjectionService == null
                ? new KnowledgeReportProjectionService() : reportProjectionService;
            for (KnowledgeReportProjectionService.ReportKnowledge item : projection.project(report))
            {
                count = insertTextChunks(source, version, report.getTaskName() + " / " + item.title(), item.content(),
                    null, null, null, report.getId(), item.evidenceJson(), item.metricId(), count);
            }
        }
        if (count == 0) throw new IOException("结构化报告没有可入库内容");
        completeChunks(version, count);
    }

    /** Vehicle weekly reports keep section-oriented chunks while linking every slice to AiReport.id. */
    private int processVehicleMarketReportChunks(KnowledgeBase source, KnowledgeVersion version, AiReport report)
        throws IOException
    {
        JSONObject root;
        try { root = JSON.parseObject(report.getReportContent()); }
        catch (Exception ex) { throw new IOException("整车市场报告不是有效JSON", ex); }
        if (root == null || root.isEmpty()) throw new IOException("整车市场报告内容为空");
        int count = 0;
        String datasetId = "";
        String remark = report.getRemark() == null ? "" : report.getRemark();
        int marker = remark.indexOf("datasetId=");
        if (marker >= 0)
        {
            int start = marker + "datasetId=".length();
            int end = remark.indexOf('|', start);
            datasetId = (end < 0 ? remark.substring(start) : remark.substring(start, end)).trim();
        }
        for (Map.Entry<String, Object> entry : root.entrySet())
        {
            if (entry.getValue() == null) continue;
            String section = entry.getKey();
            if (skipVehicleMarketSection(section)) continue;
            String value = entry.getValue() instanceof String stringValue ? stringValue
                : JSON.toJSONString(entry.getValue());
            if (value == null || value.isBlank()) continue;
            String text = "报告任务：" + report.getTaskName() + "\n数据集：" + datasetId
                + "\n章节：" + section + "\n内容：\n" + value;
            JSONObject evidence = new JSONObject(); evidence.put("kind", "MARKET_REPORT");
            evidence.put("report_id", report.getId()); evidence.put("dataset_id", datasetId);
            evidence.put("section", section);
            String metricId = "market." + section;
            if (keepVehicleMarketSectionWhole(section, value))
                count = insertAtomicChunk(source, version, report.getTaskName() + " / " + section, text, null, null,
                    version.getSourceUrl(), report.getId(), evidence.toJSONString(), metricId, count);
            else
                count = insertTextChunks(source, version, report.getTaskName() + " / " + section, text, null, null,
                    version.getSourceUrl(), report.getId(), evidence.toJSONString(), metricId, count);
        }
        return count;
    }

    /** 溯源 meta 和审计碎片会抢检索，不入库。 */
    private boolean skipVehicleMarketSection(String section)
    {
        String key = section == null ? "" : section.toLowerCase();
        return "meta".equals(key) || "content_audit".equals(key) || key.endsWith("_meta");
    }

    /** 销量结论依赖完整 JSON，不能按 1200 字切开。 */
    private boolean keepVehicleMarketSectionWhole(String section, String value)
    {
        String key = section == null ? "" : section.toLowerCase();
        if ("market_fact_pack".equals(key) || "all_available_line_charts".equals(key)
            || "line_charts".equals(key) || "rankings".equals(key))
            return true;
        return value != null && value.contains("monthly_trend");
    }

    private int insertAtomicChunk(KnowledgeBase source, KnowledgeVersion version, String title, String text,
        Integer pageStart, Integer pageEnd, String url, Long reportId, String evidenceJson, String metricId, int number)
    {
        String content = normalizeText(text);
        if (content.isBlank()) return number;
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setSourceId(source.getId()); chunk.setVersionId(version.getId()); chunk.setChunkNo(number++);
        chunk.setTitlePath(title); chunk.setContent(content);
        chunk.setSourceSnippet(content.substring(0, Math.min(300, content.length())));
        chunk.setPageStart(pageStart); chunk.setPageEnd(pageEnd); chunk.setSourceUrl(url); chunk.setReportId(reportId);
        chunk.setMetricId(metricId); chunk.setEvidenceJson(evidenceJson);
        chunk.setContentSha256(storage.sha256(content)); chunk.setTokenCount(Math.max(1, content.length() / 2));
        mapper.insertChunk(chunk);
        saveFacts(chunk);
        if (graphService != null) graphService.indexChunk(source, version, chunk);
        return number;
    }

    private int insertTextChunks(KnowledgeBase source, KnowledgeVersion version, String title, String text,
        Integer pageStart, Integer pageEnd, String url, Long reportId, String evidenceJson, int number)
    {
        String metricId = null;
        if (evidenceJson != null)
        {
            JSONObject evidence = JSON.parseObject(evidenceJson);
            metricId = evidence.getString("metric_id");
        }
        return insertTextChunks(source, version, title, text, pageStart, pageEnd, url, reportId,
            evidenceJson, metricId, number);
    }

    private int insertTextChunks(KnowledgeBase source, KnowledgeVersion version, String title, String text,
        Integer pageStart, Integer pageEnd, String url, Long reportId, String evidenceJson, String metricId, int number)
    {
        String normalized = normalizeText(text);
        if (normalized.isBlank()) return number;
        int start = 0;
        while (start < normalized.length())
        {
            int end = Math.min(normalized.length(), start + CHUNK_SIZE);
            if (end < normalized.length())
            {
                int boundary = Math.max(normalized.lastIndexOf('\n', end), normalized.lastIndexOf('。', end));
                if (boundary > start + CHUNK_SIZE / 2) end = boundary + 1;
            }
            String content = normalized.substring(start, end).trim();
            if (!content.isEmpty())
            {
                KnowledgeChunk chunk = new KnowledgeChunk();
                chunk.setSourceId(source.getId()); chunk.setVersionId(version.getId()); chunk.setChunkNo(number++);
                chunk.setTitlePath(title); chunk.setContent(content); chunk.setSourceSnippet(content.substring(0, Math.min(300, content.length())));
                chunk.setPageStart(pageStart); chunk.setPageEnd(pageEnd); chunk.setSourceUrl(url); chunk.setReportId(reportId);
                if (evidenceJson != null)
                {
                    chunk.setMetricId(metricId); chunk.setEvidenceJson(evidenceJson);
                }
                chunk.setContentSha256(storage.sha256(content)); chunk.setTokenCount(Math.max(1, content.length() / 2));
                mapper.insertChunk(chunk);
                saveFacts(chunk);
                if (graphService != null) graphService.indexChunk(source, version, chunk);
            }
            if (end >= normalized.length()) break;
            start = Math.max(start + 1, end - CHUNK_OVERLAP);
        }
        return number;
    }

    private void completeChunks(KnowledgeVersion version, int count)
    {
        version.setChunkCount(count); version.setParserVersion(PARSER_VERSION); mapper.updateVersion(version);
        KnowledgeIngestTask task = findTaskForVersion(version.getId());
        if (task != null) { task.setChunkCount(count); task.setProgress(90); task.setCurrentStage("建立检索索引"); mapper.updateIngestTask(task); }
    }

    private void updateProgress(Long versionId, int count, int progress)
    {
        KnowledgeIngestTask task = findTaskForVersion(versionId);
        if (task != null) { task.setChunkCount(count); task.setProgress(progress); mapper.updateIngestTask(task); }
    }

    private KnowledgeIngestTask findTaskForVersion(Long versionId)
    {
        return mapper.selectIngestTaskByVersionId(versionId);
    }

    public KnowledgeIngestTask reparsePdf(Long sourceId, String username)
    {
        KnowledgeBase source = requireSource(sourceId, "PDF");
        if (source.getCurrentVersionId() == null) throw new IllegalArgumentException("资料尚未入库");
        KnowledgeVersion version = mapper.selectVersionById(source.getCurrentVersionId());
        if (version == null || version.getStoredPath() == null || version.getStoredPath().isBlank())
            throw new IllegalArgumentException("找不到已入库的 PDF 文件");
        return submit(version, username, () -> processAgentDocument(source, version));
    }

    /** 用当前切片重建指标事实，不重新解析 PDF。 */
    public int rebuildFacts(Long sourceId)
    {
        KnowledgeBase source = mapper.selectKnowledgeBaseById(sourceId);
        if (source == null || source.getCurrentVersionId() == null)
            throw new IllegalArgumentException("资料不存在或尚未入库");
        Long versionId = source.getCurrentVersionId();
        mapper.deleteFactsByVersionId(versionId);
        int count = 0;
        for (KnowledgeChunk chunk : mapper.selectChunksByVersionId(versionId))
        {
            for (KnowledgeFact fact : factIndexer.extract(chunk))
            {
                mapper.insertFact(fact);
                count++;
            }
        }
        return count;
    }

    public List<KnowledgeFact> searchFacts(List<String> hints, List<Long> sourceIds)
    {
        if (hints == null || hints.isEmpty() || sourceIds == null || sourceIds.isEmpty()) return List.of();
        List<String> usable = hints.stream().filter(hint -> hint != null && hint.trim().length() >= 2).distinct().toList();
        if (usable.isEmpty()) return List.of();
        List<Long> ids = sourceIds.stream().filter(id -> id != null).distinct().toList();
        if (ids.isEmpty()) return List.of();
        List<KnowledgeFact> found = mapper.searchCurrentFacts(usable, ids);
        return found == null ? List.of() : found;
    }

    private void saveFacts(KnowledgeChunk chunk)
    {
        if (chunk == null || chunk.getId() == null) return;
        for (KnowledgeFact fact : factIndexer.extract(chunk)) mapper.insertFact(fact);
    }

    private KnowledgeVersion createVersion(KnowledgeBase source, String versionNo, String originalName,
        String storedPath, String sourceUrl, String hash, String username)
    {
        KnowledgeVersion existing = mapper.selectVersionByHash(source.getId(), hash);
        if (existing != null) throw new IllegalArgumentException("该内容已入库，版本：" + existing.getVersionNo());
        KnowledgeVersion version = new KnowledgeVersion();
        version.setSourceId(source.getId());
        version.setVersionNo(versionNo == null || versionNo.isBlank()
            ? LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss")) : versionNo.trim());
        version.setOriginalName(originalName); version.setStoredPath(storedPath); version.setSourceUrl(sourceUrl);
        version.setContentSha256(hash); version.setParserVersion(PARSER_VERSION); version.setPageCount(0); version.setChunkCount(0);
        version.setStatus("0"); version.setErrorMessage(""); version.setCreateBy(username); mapper.insertVersion(version);
        return version;
    }

    private KnowledgeBase requireSource(Long id, String type)
    {
        KnowledgeBase source = mapper.selectKnowledgeBaseById(id);
        if (source == null) throw new IllegalArgumentException("知识库资料不存在");
        if (!type.equalsIgnoreCase(source.getSourceType())) throw new IllegalArgumentException("资料类型必须是 " + type);
        if (!"1".equals(source.getEnabled())) throw new IllegalArgumentException("资料已停用");
        return source;
    }

    private URI validateExternalUri(String url)
    {
        if (url == null || url.isBlank()) throw new IllegalArgumentException("来源URL不能为空");
        URI uri = URI.create(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme)) || host.isEmpty()) throw new IllegalArgumentException("来源URL无效");
        return uri;
    }

    private LocalDateTime parsePublishedTime(String value)
    {
        String clean = clean(value);
        if (clean.isBlank()) return null;
        try { return LocalDate.parse(clean.replace('/', '-')).atStartOfDay(); }
        catch (Exception ignored) { return null; }
    }

    private void validateNewsUri(URI uri)
    {
        if (allowedNewsDomains.isEmpty()) throw new IllegalStateException("未提供新闻正文，且尚未配置新闻抓取域名白名单");
        String host = uri.getHost().toLowerCase(Locale.ROOT);
        boolean allowed = allowedNewsDomains.stream().anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
        if (!allowed) throw new IllegalArgumentException("新闻URL不在允许域名白名单中");
    }

    private void clearGraph(Long versionId)
    {
        if (graphService != null) graphService.clearVersion(versionId);
    }

    private String htmlToText(String html)
    {
        return normalizeText(html.replaceAll("(?is)<script.*?</script>", " ").replaceAll("(?is)<style.*?</style>", " ")
            .replaceAll("(?is)<[^>]+>", "\n").replace("&nbsp;", " ").replace("&amp;", "&")
            .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'"));
    }

    private String normalizeText(String text)
    {
        if (text == null) return "";
        return text.replace('\u0000', ' ').replaceAll("[\\t\\x0B\\f\\r]+", " ")
            .replaceAll(" *\\n *", "\n").replaceAll("\\n{3,}", "\n\n").replaceAll(" {2,}", " ").trim();
    }

    private void fail(KnowledgeIngestTask task, KnowledgeVersion version, String message)
    {
        String safe = message == null ? "知识库入库失败" : message.substring(0, Math.min(1000, message.length()));
        if (task != null) { task.setStatus("3"); task.setProgress(100); task.setCurrentStage("失败"); task.setErrorMessage(safe); task.setFinishedTime(LocalDateTime.now()); mapper.updateIngestTask(task); }
        if (version != null) { version.setStatus("3"); version.setErrorMessage(safe); mapper.updateVersion(version); }
    }

    @FunctionalInterface private interface ThrowingRunnable { void run() throws Exception; }

    private record NewsArticle(String id, String sourceName, String sourceSite, String title, String content,
        String publishedAt, String crawledAt, String url, String contentHash) {}
}
