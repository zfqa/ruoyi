package com.ruoyi.business.ai.controller;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import com.ruoyi.business.knowledge.service.KnowledgeQaService;
import com.ruoyi.business.knowledge.service.LlmRuntimeConfiguration;
import com.ruoyi.common.annotation.Log;
import com.ruoyi.common.core.controller.BaseController;
import com.ruoyi.common.core.domain.AjaxResult;
import com.ruoyi.common.enums.BusinessType;

/**
 * 系统统一 AI/LLM 配置入口。
 * 凭证由 {@link LlmRuntimeConfiguration} 持久化到 sys_config，并下发给整车、车载、知识库、文本与侧车。
 */
@RestController
@RequestMapping("/business/ai/config")
public class AiConfigController extends BaseController
{
    private final LlmRuntimeConfiguration llmRuntimeConfiguration;
    private final KnowledgeQaService knowledgeQaService;

    @Value("${business.text.llm.timeout-seconds:300}")
    private int textTimeoutSeconds;

    @Value("${business.knowledge.llm.timeout-seconds:300}")
    private int knowledgeTimeoutSeconds;

    @Value("${business.excel.llm.table-timeout-seconds:30}")
    private int excelTableTimeoutSeconds;

    @Value("${business.excel.llm.report-timeout-seconds:180}")
    private int excelReportTimeoutSeconds;

    @Value("${business.excel.llm.max-retries:0}")
    private int excelMaxRetries;

    @Value("${business.excel.llm.max-table-calls:4}")
    private int excelMaxTableCalls;

    @Value("${business.market-agent.base-url:http://127.0.0.1:8001/api}")
    private String marketAgentBaseUrl;

    @Value("${agent-service.base-url:http://127.0.0.1:8000}")
    private String agentServiceBaseUrl;

    public AiConfigController(LlmRuntimeConfiguration llmRuntimeConfiguration, KnowledgeQaService knowledgeQaService)
    {
        this.llmRuntimeConfiguration = llmRuntimeConfiguration;
        this.knowledgeQaService = knowledgeQaService;
    }

    @PreAuthorize("@ss.hasPermi('business:ai:config:query')")
    @GetMapping
    public AjaxResult getConfig()
    {
        Map<String, Object> body = new LinkedHashMap<>(llmRuntimeConfiguration.view());
        body.put("appliesTo", featureScopes());
        body.put("featureLimits", featureLimits());
        body.put("sidecars", sidecarStatus());
        body.put("notes", List.of(
            "默认 DeepSeek（OpenAI Chat Completions 兼容）。可填写完整 .../chat/completions，或只填 .../v1，系统会自动规范化。",
            "接口地址、模型、API Key 保存后立即生效，并写入 MySQL（密钥加密）。切换厂商后请重新填写对应 API Key。",
            "超时与侧车地址当前读取 application.yml / 环境变量，修改后需重启 Java 服务。",
            "整车 market-agent、车载 excel-agent、文档侧车 agent-service 在调用时自动使用本页凭证。"
        ));
        return success(body);
    }

    @PreAuthorize("@ss.hasPermi('business:ai:config:edit')")
    @Log(title = "AI配置", businessType = BusinessType.UPDATE)
    @PutMapping
    public AjaxResult updateConfig(@RequestBody Map<String, Object> payload)
    {
        try
        {
            Map<String, Object> updated = llmRuntimeConfiguration.update(
                string(payload.get("apiUrl")),
                string(payload.get("model")),
                string(payload.get("apiKey")),
                Boolean.parseBoolean(string(payload.get("clearApiKey"))));
            Map<String, Object> body = new LinkedHashMap<>(updated);
            body.put("appliesTo", featureScopes());
            body.put("featureLimits", featureLimits());
            body.put("sidecars", sidecarStatus());
            return success(body);
        }
        catch (Exception e)
        {
            return AjaxResult.error(e.getMessage());
        }
    }

    @PreAuthorize("@ss.hasPermi('business:ai:config:edit')")
    @Log(title = "AI配置测试", businessType = BusinessType.OTHER)
    @PostMapping("/test")
    public AjaxResult testConfig()
    {
        try
        {
            return success(knowledgeQaService.testLlmConfiguration());
        }
        catch (Exception e)
        {
            return AjaxResult.error(e.getMessage());
        }
    }

    private List<Map<String, Object>> featureScopes()
    {
        List<Map<String, Object>> list = new ArrayList<>();
        list.add(scope("vehicle", "整车市场分析", "周报生成、对话编排、LLM 表述", true));
        list.add(scope("onboard", "车载市场分析", "Excel 表结构识别与竞争洞察报告叙事", true));
        list.add(scope("knowledge", "固定知识库问答", "可溯源回答与引用校验", true));
        list.add(scope("text", "文本结构化", "实体/字段抽取增强", true));
        list.add(scope("document", "PDF/PPTX 语义解析", "经 agent-service 下发同一套凭证", true));
        return list;
    }

    private Map<String, Object> featureLimits()
    {
        Map<String, Object> limits = new LinkedHashMap<>();
        limits.put("textTimeoutSeconds", textTimeoutSeconds);
        limits.put("knowledgeTimeoutSeconds", knowledgeTimeoutSeconds);
        limits.put("excelTableTimeoutSeconds", excelTableTimeoutSeconds);
        limits.put("excelReportTimeoutSeconds", excelReportTimeoutSeconds);
        limits.put("excelMaxRetries", excelMaxRetries);
        limits.put("excelMaxTableCalls", excelMaxTableCalls);
        limits.put("editable", false);
        limits.put("source", "application.yml / 环境变量");
        return limits;
    }

    private Map<String, Object> sidecarStatus()
    {
        Map<String, Object> sidecars = new LinkedHashMap<>();
        sidecars.put("marketAgentBaseUrl", marketAgentBaseUrl);
        sidecars.put("agentServiceBaseUrl", agentServiceBaseUrl);
        sidecars.put("editable", false);
        sidecars.put("source", "application.yml / 环境变量");
        return sidecars;
    }

    private Map<String, Object> scope(String code, String name, String usage, boolean usesSharedCredentials)
    {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("code", code);
        item.put("name", name);
        item.put("usage", usage);
        item.put("usesSharedCredentials", usesSharedCredentials);
        return item;
    }

    private String string(Object value)
    {
        return value == null ? "" : String.valueOf(value).trim();
    }
}
