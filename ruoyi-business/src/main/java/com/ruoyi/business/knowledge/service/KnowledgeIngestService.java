package com.ruoyi.business.knowledge.service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.ruoyi.business.knowledge.domain.KnowledgeBase;
import com.ruoyi.business.knowledge.domain.KnowledgeChunk;
import com.ruoyi.business.knowledge.domain.KnowledgeIngestTask;
import com.ruoyi.business.knowledge.domain.KnowledgeVersion;
import com.ruoyi.business.knowledge.mapper.KnowledgeBaseMapper;
import com.ruoyi.business.report.domain.AiReport;
import com.ruoyi.business.report.service.IAiReportService;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

@Service
public class KnowledgeIngestService
{
    private static final String PARSER_VERSION = "kb-poc-1";
    private static final int CHUNK_SIZE = 1200;
    private static final int CHUNK_OVERLAP = 120;

    private final KnowledgeBaseMapper mapper;
    private final KnowledgeFileStorage storage;
    private final IAiReportService reportService;
    private final ThreadPoolTaskExecutor executor;
    private final Set<String> allowedNewsDomains;
    private final HttpClient httpClient;

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

    public KnowledgeIngestTask submitPdf(Long sourceId, String versionNo, MultipartFile file, String username) throws IOException
    {
        KnowledgeBase source = requireSource(sourceId, "PDF");
        KnowledgeFileStorage.StoredFile stored = storage.savePdf(file);
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

    public KnowledgeIngestTask submitNews(Long sourceId, String versionNo, String url, String title, String username) throws Exception
    {
        KnowledgeBase source = requireSource(sourceId, "NEWS");
        URI uri = validateNewsUri(url);
        String actualTitle = title == null || title.isBlank() ? source.getSourceName() : title.trim();
        String provisionalHash = storage.sha256("PENDING:" + uri + ":" + System.nanoTime());
        KnowledgeVersion version = createVersion(source, versionNo, actualTitle, "", uri.toString(), provisionalHash, username);
        return submit(version, username, () -> processNews(source, version, actualTitle, uri));
    }

    public KnowledgeIngestTask submitReport(Long sourceId, String versionNo, Long reportId, String username)
    {
        KnowledgeBase source = requireSource(sourceId, "REPORT");
        AiReport report = reportService.selectAiReportById(reportId);
        if (report == null || report.getReportContent() == null || report.getReportContent().isBlank())
            throw new IllegalArgumentException("结构化报告不存在或内容为空");
        String hash = storage.sha256(report.getReportContent());
        KnowledgeVersion version = createVersion(source, versionNo, report.getTaskName(), "", "", hash, username);
        return submit(version, username, () -> processReport(source, version, report));
    }

    public KnowledgeIngestTask getTask(Long id) { return mapper.selectIngestTaskById(id); }

    public List<KnowledgeVersion> getVersions(Long sourceId) { return mapper.selectVersionsBySourceId(sourceId); }

    public List<KnowledgeChunk> search(String query, String sourceType, List<Long> roleIds, boolean admin, int limit)
    {
        if (query == null || query.trim().length() < 2) throw new IllegalArgumentException("检索词至少2个字符");
        String actualQuery = query.trim();
        List<String> entityTerms = KnowledgeTextProcessor.detectEntityTerms(actualQuery);
        List<KnowledgeChunk> chunks = mapper.searchChunks(actualQuery, sourceType, roleIds, admin,
            Math.max(1, Math.min(limit, 50)), entityTerms, KnowledgeTextProcessor.normalizedLiteral(actualQuery));
        for (KnowledgeChunk chunk : chunks)
        {
            String cleaned = KnowledgeTextProcessor.cleanPdfText(chunk.getContent());
            if (!cleaned.isBlank()) chunk.setContent(cleaned);
            chunk.setSourceSnippet(KnowledgeTextProcessor.buildSnippet(chunk.getContent(), actualQuery, entityTerms, 500));
        }
        return chunks;
    }

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
            version.setStatus("2"); version.setErrorMessage(""); mapper.updateVersion(version);
            KnowledgeBase source = mapper.selectKnowledgeBaseById(version.getSourceId());
            source.setCurrentVersionId(version.getId()); source.setStatus("2"); source.setRemark("当前版本：" + version.getVersionNo());
            mapper.updateKnowledgeBase(source);
        }
        catch (Exception e)
        {
            fail(task, version, e.getMessage() == null ? "知识库入库失败" : e.getMessage());
        }
    }

    private void processPdf(KnowledgeBase source, KnowledgeVersion version) throws IOException
    {
        mapper.deleteChunksByVersionId(version.getId());
        int number = 0;
        try (PDDocument document = Loader.loadPDF(Path.of(version.getStoredPath()).toFile()))
        {
            PDFTextStripper stripper = new PDFTextStripper();
            version.setPageCount(document.getNumberOfPages());
            for (int page = 1; page <= document.getNumberOfPages(); page++)
            {
                stripper.setStartPage(page); stripper.setEndPage(page);
                String text = KnowledgeTextProcessor.cleanPdfText(stripper.getText(document));
                number = insertTextChunks(source, version, source.getSourceName(), text, page, page, null, null, null, number);
                updateProgress(version.getId(), number, 10 + (int)(75.0 * page / Math.max(1, document.getNumberOfPages())));
            }
        }
        if (number == 0) throw new IOException("PDF未提取到文字，可能是扫描版文件，需要OCR");
        completeChunks(version, number);
    }

    private void processText(KnowledgeBase source, KnowledgeVersion version, String title, String text, Long reportId) throws IOException
    {
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

    private void processReport(KnowledgeBase source, KnowledgeVersion version, AiReport report) throws IOException
    {
        mapper.deleteChunksByVersionId(version.getId());
        int count = 0;
        JSONObject root = JSON.parseObject(report.getReportContent());
        JSONArray evidence = root.getJSONArray("evidence");
        if (evidence != null)
        {
            for (int i = 0; i < evidence.size(); i++)
            {
                JSONObject item = evidence.getJSONObject(i);
                String metricId = item.getString("metric_id");
                Object metric = findMetric(root, metricId);
                String content = metric == null ? "指标 " + metricId : JSON.toJSONString(metric);
                count = insertTextChunks(source, version, report.getTaskName() + " / " + metricId, content,
                    null, null, null, report.getId(), item.toJSONString(), count);
            }
        }
        count = insertTextChunks(source, version, report.getTaskName(), report.getReportContent(),
            null, null, null, report.getId(), null, count);
        if (count == 0) throw new IOException("结构化报告没有可入库内容");
        completeChunks(version, count);
    }

    private int insertTextChunks(KnowledgeBase source, KnowledgeVersion version, String title, String text,
        Integer pageStart, Integer pageEnd, String url, Long reportId, String evidenceJson, int number)
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
                    JSONObject e = JSON.parseObject(evidenceJson); chunk.setMetricId(e.getString("metric_id")); chunk.setEvidenceJson(evidenceJson);
                }
                chunk.setContentSha256(storage.sha256(content)); chunk.setTokenCount(Math.max(1, content.length() / 2));
                mapper.insertChunk(chunk);
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

    private URI validateNewsUri(String url)
    {
        if (allowedNewsDomains.isEmpty()) throw new IllegalStateException("尚未配置新闻抓取域名白名单");
        URI uri = URI.create(url);
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase(Locale.ROOT);
        if (!("http".equals(scheme) || "https".equals(scheme)) || host.isEmpty()) throw new IllegalArgumentException("新闻URL无效");
        boolean allowed = allowedNewsDomains.stream().anyMatch(domain -> host.equals(domain) || host.endsWith("." + domain));
        if (!allowed) throw new IllegalArgumentException("新闻URL不在允许域名白名单中");
        return uri;
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

    private Object findMetric(Object node, String metricId)
    {
        if (metricId == null || node == null) return null;
        if (node instanceof JSONObject object)
        {
            if (metricId.equals(object.getString("metric_id"))) return object;
            for (Object child : object.values()) { Object found = findMetric(child, metricId); if (found != null) return found; }
        }
        else if (node instanceof JSONArray array)
        {
            for (Object child : array) { Object found = findMetric(child, metricId); if (found != null) return found; }
        }
        return null;
    }

    private void fail(KnowledgeIngestTask task, KnowledgeVersion version, String message)
    {
        String safe = message == null ? "知识库入库失败" : message.substring(0, Math.min(1000, message.length()));
        if (task != null) { task.setStatus("3"); task.setProgress(100); task.setCurrentStage("失败"); task.setErrorMessage(safe); task.setFinishedTime(LocalDateTime.now()); mapper.updateIngestTask(task); }
        if (version != null) { version.setStatus("3"); version.setErrorMessage(safe); mapper.updateVersion(version); }
    }

    @FunctionalInterface private interface ThrowingRunnable { void run() throws Exception; }
}
