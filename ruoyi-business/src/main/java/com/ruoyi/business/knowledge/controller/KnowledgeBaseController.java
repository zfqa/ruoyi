package com.ruoyi.business.knowledge.controller;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Autowired;
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
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.core.page.TableDataInfo;
import com.ruoyi.common.enums.BusinessType;
import com.ruoyi.common.utils.poi.ExcelUtil;
import com.ruoyi.business.knowledge.domain.KnowledgeBase;
import com.ruoyi.business.knowledge.domain.KnowledgeVersion;
import com.ruoyi.business.knowledge.service.IKnowledgeBaseService;
import com.ruoyi.business.knowledge.service.KnowledgeIngestService;
import com.ruoyi.business.knowledge.service.KnowledgeQaService;
import com.ruoyi.business.knowledge.service.KnowledgeQaTaskService;
import com.ruoyi.business.knowledge.service.KnowledgeGraphService;

/**
 * 固定文件知识库及来源展示 控制器
 * 
 * @author ruoyi
 */
@RestController
@RequestMapping("/business/knowledge")
public class KnowledgeBaseController extends BaseController
{
    @Autowired
    private IKnowledgeBaseService knowledgeBaseService;

    @Autowired
    private KnowledgeIngestService knowledgeIngestService;

    @Autowired
    private KnowledgeQaService knowledgeQaService;

    @Autowired
    private KnowledgeQaTaskService knowledgeQaTaskService;

    @Autowired
    private KnowledgeGraphService knowledgeGraphService;

    @PreAuthorize("@ss.hasPermi('business:knowledge:list')")
    @GetMapping("/list")
    public TableDataInfo list(KnowledgeBase knowledgeBase)
    {
        startPage();
        List<KnowledgeBase> list = knowledgeBaseService.selectKnowledgeBaseList(knowledgeBase);
        return getDataTable(list);
    }

    @PreAuthorize("@ss.hasPermi('business:knowledge:export')")
    @Log(title = "固定文件知识库及来源展示", businessType = BusinessType.EXPORT)
    @PostMapping("/export")
    public void export(HttpServletResponse response, KnowledgeBase knowledgeBase)
    {
        List<KnowledgeBase> list = knowledgeBaseService.selectKnowledgeBaseList(knowledgeBase);
        ExcelUtil<KnowledgeBase> util = new ExcelUtil<KnowledgeBase>(KnowledgeBase.class);
        util.exportExcel(response, list, "固定文件知识库及来源展示数据");
    }

    @PreAuthorize("@ss.hasPermi('business:knowledge:query')")
    @GetMapping(value = "/{id}")
    public AjaxResult getInfo(@PathVariable("id") Long id)
    {
        return success(knowledgeBaseService.selectKnowledgeBaseById(id));
    }

    @PreAuthorize("@ss.hasPermi('business:knowledge:add')")
    @Log(title = "固定文件知识库及来源展示", businessType = BusinessType.INSERT)
    @PostMapping
    public AjaxResult add(@RequestBody KnowledgeBase knowledgeBase)
    {
        knowledgeBase.setCreateBy(getUsername());
        if (knowledgeBase.getSourceType() == null || knowledgeBase.getSourceType().isBlank()) knowledgeBase.setSourceType("PDF");
        if (knowledgeBase.getConfidentiality() == null || knowledgeBase.getConfidentiality().isBlank()) knowledgeBase.setConfidentiality("INTERNAL");
        if (knowledgeBase.getEnabled() == null || knowledgeBase.getEnabled().isBlank()) knowledgeBase.setEnabled("1");
        if (knowledgeBase.getStatus() == null || knowledgeBase.getStatus().isBlank()) knowledgeBase.setStatus("0");
        return toAjax(knowledgeBaseService.insertKnowledgeBase(knowledgeBase));
    }

    @PreAuthorize("@ss.hasPermi('business:knowledge:edit')")
    @Log(title = "固定文件知识库及来源展示", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult edit(@RequestBody KnowledgeBase knowledgeBase)
    {
        knowledgeBase.setUpdateBy(getUsername());
        return toAjax(knowledgeBaseService.updateKnowledgeBase(knowledgeBase));
    }

    @PreAuthorize("@ss.hasPermi('business:knowledge:remove')")
    @Log(title = "固定文件知识库及来源展示", businessType = BusinessType.DELETE)
    @DeleteMapping("/{ids}")
    public AjaxResult remove(@PathVariable Long[] ids)
    {
        for (Long id : ids)
        {
            KnowledgeBase source = knowledgeBaseService.selectKnowledgeBaseById(id);
            if (source != null && source.getCurrentVersionId() != null)
                return AjaxResult.error("资料已有入库版本，为保留来源链不允许删除；如不再使用，请将资料设为停用");
        }
        return toAjax(knowledgeBaseService.deleteKnowledgeBaseByIds(ids));
    }

    @PreAuthorize("@ss.hasPermi('business:knowledge:add')")
    @Log(title = "PDF知识库入库", businessType = BusinessType.IMPORT)
    @PostMapping("/ingest/pdf")
    public AjaxResult ingestPdf(@RequestParam("sourceId") Long sourceId,
        @RequestParam(value = "versionNo", required = false) String versionNo,
        @RequestParam("file") MultipartFile file)
    {
        try
        {
            return success(knowledgeIngestService.submitPdf(sourceId, versionNo, file, getUsername()));
        }
        catch (Exception e)
        {
            return AjaxResult.error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:knowledge:add')")
    @Log(title = "新闻知识库入库", businessType = BusinessType.IMPORT)
    @PostMapping("/ingest/news")
    public AjaxResult ingestNews(@RequestBody Map<String, Object> payload)
    {
        try
        {
            Long sourceId = Long.valueOf(String.valueOf(payload.get("sourceId")));
            return success(knowledgeIngestService.submitNews(sourceId, string(payload.get("versionNo")),
                string(payload.get("url")), string(payload.get("title")), string(payload.get("content")), getUsername()));
        }
        catch (Exception e)
        {
            return AjaxResult.error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:knowledge:add')")
    @Log(title = "批量新闻JSON知识库入库", businessType = BusinessType.IMPORT)
    @PostMapping("/ingest/news-json")
    public AjaxResult ingestNewsJson(@RequestParam("sourceId") Long sourceId,
        @RequestParam(value = "versionNo", required = false) String versionNo,
        @RequestParam("file") MultipartFile file)
    {
        try
        {
            return success(knowledgeIngestService.submitNewsJson(sourceId, versionNo, file, getUsername()));
        }
        catch (Exception e)
        {
            return AjaxResult.error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:knowledge:add')")
    @Log(title = "政策知识库入库", businessType = BusinessType.IMPORT)
    @PostMapping("/ingest/policy")
    public AjaxResult ingestPolicy(@RequestBody Map<String, Object> payload)
    {
        try
        {
            Long sourceId = Long.valueOf(String.valueOf(payload.get("sourceId")));
            return success(knowledgeIngestService.submitPolicy(sourceId, string(payload.get("versionNo")),
                string(payload.get("url")), string(payload.get("title")), string(payload.get("content")),
                string(payload.get("issuedBy")), string(payload.get("publishedAt")),
                string(payload.get("policyLevel")), getUsername()));
        }
        catch (Exception e)
        {
            return AjaxResult.error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:knowledge:add')")
    @Log(title = "结构化报告知识库入库", businessType = BusinessType.IMPORT)
    @PostMapping("/ingest/report")
    public AjaxResult ingestReport(@RequestBody Map<String, Object> payload)
    {
        try
        {
            Long sourceId = Long.valueOf(String.valueOf(payload.get("sourceId")));
            Long reportId = Long.valueOf(String.valueOf(payload.get("reportId")));
            return success(knowledgeIngestService.submitReport(sourceId, string(payload.get("versionNo")), reportId, getUsername()));
        }
        catch (Exception e)
        {
            return AjaxResult.error(e.getMessage());
        }
    }

    /** 为启用自动入库前已生成的成功报告补建独立知识源。 */
    @PreAuthorize("@ss.hasPermi('business:knowledge:add')")
    @Log(title = "生成报告自动知识入库", businessType = BusinessType.IMPORT)
    @PostMapping("/ingest/generated-report/{reportId}")
    public AjaxResult ingestGeneratedReport(@PathVariable Long reportId)
    {
        try
        {
            return success(knowledgeIngestService.submitGeneratedReport(reportId, getUsername()));
        }
        catch (Exception e)
        {
            return AjaxResult.error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:knowledge:query')")
    @GetMapping("/task/{id}")
    public AjaxResult task(@PathVariable Long id)
    {
        Object task = knowledgeIngestService.getTask(id);
        return task == null ? AjaxResult.error("入库任务不存在") : success(task);
    }

    @PreAuthorize("@ss.hasPermi('business:knowledge:query')")
    @GetMapping("/{sourceId}/versions")
    public AjaxResult versions(@PathVariable Long sourceId)
    {
        return success(knowledgeIngestService.getVersions(sourceId).stream().map(this::versionView).toList());
    }

    @PreAuthorize("@ss.hasPermi('business:knowledge:query')")
    @GetMapping("/search")
    public AjaxResult search(@RequestParam("q") String query,
        @RequestParam(value = "sourceType", required = false) String sourceType,
        @RequestParam(value = "limit", defaultValue = "20") int limit)
    {
        try
        {
            List<Long> roleIds = getLoginUser().getUser().getRoles() == null ? List.of()
                : getLoginUser().getUser().getRoles().stream().map(role -> role.getRoleId()).collect(Collectors.toList());
            return success(knowledgeIngestService.search(query, sourceType, roleIds,
                getLoginUser().getUser().isAdmin(), limit));
        }
        catch (Exception e)
        {
            return AjaxResult.error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:knowledge:query')")
    @PostMapping("/qa")
    public AjaxResult ask(@RequestBody Map<String, Object> payload)
    {
        try
        {
            List<Long> roleIds = getLoginUser().getUser().getRoles() == null ? List.of()
                : getLoginUser().getUser().getRoles().stream().map(role -> role.getRoleId()).collect(Collectors.toList());
            boolean includeNews = payload.get("includeNews") == null
                || Boolean.parseBoolean(string(payload.get("includeNews")));
            return success(knowledgeQaService.ask(string(payload.get("question")), string(payload.get("sourceType")),
                roleIds, getLoginUser().getUser().isAdmin(), includeNews));
        }
        catch (Exception e)
        {
            return AjaxResult.error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:knowledge:query')")
    @PostMapping("/qa-tasks")
    public AjaxResult submitQaTask(@RequestBody Map<String, Object> payload)
    {
        try
        {
            boolean includeNews = payload.get("includeNews") == null
                || Boolean.parseBoolean(string(payload.get("includeNews")));
            return success(knowledgeQaTaskService.submit(string(payload.get("question")),
                string(payload.get("sourceType")), includeNews, roleIds(),
                getLoginUser().getUser().isAdmin(), getUsername()));
        }
        catch (Exception e)
        {
            return AjaxResult.error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:knowledge:query')")
    @GetMapping("/qa-tasks/{taskId}")
    public AjaxResult qaTask(@PathVariable String taskId)
    {
        try
        {
            return success(knowledgeQaTaskService.get(taskId, getUsername(),
                getLoginUser().getUser().isAdmin()));
        }
        catch (Exception e)
        {
            return AjaxResult.error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:knowledge:query')")
    @GetMapping("/llm-config")
    public AjaxResult llmConfig()
    {
        return success(knowledgeQaService.llmConfiguration());
    }

    @PreAuthorize("@ss.hasPermi('business:knowledge:edit')")
    @PutMapping("/llm-config")
    public AjaxResult updateLlmConfig(@RequestBody Map<String, Object> payload)
    {
        try
        {
            return success(knowledgeQaService.updateLlmConfiguration(string(payload.get("apiUrl")),
                string(payload.get("model")), string(payload.get("apiKey")),
                Boolean.parseBoolean(string(payload.get("clearApiKey")))));
        }
        catch (Exception e) { return AjaxResult.error(e.getMessage()); }
    }

    @PreAuthorize("@ss.hasPermi('business:knowledge:edit')")
    @PostMapping("/llm-config/test")
    public AjaxResult testLlmConfig()
    {
        try { return success(knowledgeQaService.testLlmConfiguration()); }
        catch (Exception e) { return AjaxResult.error(e.getMessage()); }
    }

    @PreAuthorize("@ss.hasPermi('business:knowledge:query')")
    @GetMapping("/graph")
    public AjaxResult graph(@RequestParam(value = "period", required = false) String period,
        @RequestParam(value = "dataType", required = false) String dataType,
        @RequestParam(value = "centerId", required = false) Long centerId,
        @RequestParam(value = "limit", defaultValue = "300") int limit)
    {
        return success(knowledgeGraphService.graph(period, dataType, centerId, roleIds(),
            getLoginUser().getUser().isAdmin(), limit));
    }

    @PreAuthorize("@ss.hasPermi('business:knowledge:edit')")
    @Log(title = "重建知识图谱", businessType = BusinessType.UPDATE)
    @PostMapping("/graph/rebuild")
    public AjaxResult rebuildGraph()
    {
        return success(knowledgeGraphService.rebuildCurrentGraph());
    }

    @PreAuthorize("@ss.hasPermi('business:knowledge:query')")
    @GetMapping("/evidence/{chunkId}")
    public AjaxResult evidence(@PathVariable Long chunkId,
        @RequestParam(value = "startOffset", required = false) Integer startOffset,
        @RequestParam(value = "endOffset", required = false) Integer endOffset)
    {
        try
        {
            return success(knowledgeGraphService.evidence(chunkId, startOffset, endOffset, roleIds(),
                getLoginUser().getUser().isAdmin()));
        }
        catch (Exception e)
        {
            return AjaxResult.error(e.getMessage());
        }
    }

    private List<Long> roleIds()
    {
        return getLoginUser().getUser().getRoles() == null ? List.of()
            : getLoginUser().getUser().getRoles().stream().map(role -> role.getRoleId()).collect(Collectors.toList());
    }

    private String string(Object value)
    {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** 只返回溯源所需元数据，不向浏览器暴露服务器文件路径和内容哈希。 */
    private Map<String, Object> versionView(KnowledgeVersion version)
    {
        Map<String, Object> value = new java.util.LinkedHashMap<>();
        value.put("id", version.getId());
        value.put("sourceId", version.getSourceId());
        value.put("versionNo", version.getVersionNo());
        value.put("originalName", version.getOriginalName());
        value.put("sourceUrl", version.getSourceUrl());
        value.put("publishedTime", version.getPublishedTime());
        value.put("fetchedTime", version.getFetchedTime());
        value.put("parserVersion", version.getParserVersion());
        value.put("pageCount", version.getPageCount());
        value.put("chunkCount", version.getChunkCount());
        value.put("status", version.getStatus());
        value.put("errorMessage", version.getErrorMessage());
        value.put("createBy", version.getCreateBy());
        value.put("createTime", version.getCreateTime());
        return value;
    }
}
