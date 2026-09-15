package com.ruoyi.business.knowledge.service;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.ruoyi.system.domain.SysConfig;
import com.ruoyi.system.service.ISysConfigService;

/**
 * 整车市场分析、报告生成、文本结构化和知识问答共用的LLM配置。
 * 前端保存后持久化到sys_config，API Key加密保存且读取接口永不返回密钥内容。
 */
@Service
public class LlmRuntimeConfiguration
{
    private static final String URL_KEY = "business.llm.apiUrl";
    private static final String MODEL_KEY = "business.llm.model";
    private static final String API_KEY = "business.llm.apiKeyEncrypted";
    private static final String CLEARED = "__CLEARED__";
    private static final String CIPHER_PREFIX = "v1:";
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private volatile String apiUrl;
    private volatile String model;
    private volatile String apiKey;
    private final ISysConfigService configService;
    private final String persistenceSecret;

    @Autowired
    public LlmRuntimeConfiguration(
        @Value("${business.llm.api-url:https://ark.cn-beijing.volces.com/api/v3/chat/completions}") String apiUrl,
        @Value("${business.llm.model:glm-5-2-260617}") String model,
        @Value("${business.llm.api-key:}") String apiKey,
        @Value("${business.llm.persistence-secret:${token.secret:}}") String persistenceSecret,
        ISysConfigService configService)
    {
        this.apiUrl = validateApiUrl(apiUrl);
        this.model = validateModel(model);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.persistenceSecret = persistenceSecret == null ? "" : persistenceSecret;
        this.configService = configService;
    }

    /** 供不启动Spring/MySQL的单元测试使用。 */
    public LlmRuntimeConfiguration(String apiUrl, String model, String apiKey)
    {
        this.apiUrl = validateApiUrl(apiUrl);
        this.model = validateModel(model);
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.persistenceSecret = "unit-test-only";
        this.configService = null;
    }

    @PostConstruct
    public synchronized void loadPersistedConfiguration()
    {
        if (configService == null) return;
        String savedUrl = configService.selectConfigByKey(URL_KEY);
        String savedModel = configService.selectConfigByKey(MODEL_KEY);
        String savedKey = configService.selectConfigByKey(API_KEY);
        if (!savedUrl.isBlank()) apiUrl = validateApiUrl(savedUrl);
        if (!savedModel.isBlank()) model = validateModel(savedModel);
        if (CLEARED.equals(savedKey)) apiKey = "";
        else if (!savedKey.isBlank()) apiKey = decryptApiKey(savedKey);
    }

    public String getApiUrl() { return apiUrl; }
    public String getModel() { return model; }
    public String getApiKey() { return apiKey; }

    public Map<String, Object> view()
    {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("apiUrl", apiUrl);
        value.put("model", model);
        value.put("apiKeyConfigured", apiKey != null && !apiKey.isBlank());
        value.put("appliesTo", "VEHICLE_MARKET_REPORT_TEXT_EXTRACTION_AND_KNOWLEDGE_QA");
        value.put("apiKeyStorage", "MYSQL_ENCRYPTED");
        value.put("restartBehavior", "服务重启后优先加载MySQL持久化配置；未保存时才回退环境变量");
        return value;
    }

    @Transactional(rollbackFor = Exception.class)
    public synchronized Map<String, Object> update(String newApiUrl, String newModel,
        String newApiKey, boolean clearApiKey)
    {
        String candidateUrl = newApiUrl == null || newApiUrl.isBlank() ? apiUrl : validateApiUrl(newApiUrl.trim());
        String candidateModel = newModel == null || newModel.isBlank() ? model : validateModel(newModel.trim());
        String candidateKey = clearApiKey ? ""
            : newApiKey == null || newApiKey.isBlank() ? apiKey : newApiKey.trim();
        if (configService != null)
        {
            saveConfig(URL_KEY, "LLM请求地址", candidateUrl);
            saveConfig(MODEL_KEY, "LLM模型名称", candidateModel);
            saveConfig(API_KEY, "LLM API Key（密文）", candidateKey.isBlank() ? CLEARED : encryptApiKey(candidateKey));
        }
        apiUrl = candidateUrl;
        model = candidateModel;
        apiKey = candidateKey;
        return view();
    }

    private void saveConfig(String key, String name, String value)
    {
        SysConfig query = new SysConfig();
        query.setConfigKey(key);
        List<SysConfig> matches = configService.selectConfigList(query);
        SysConfig existing = matches.stream().filter(item -> key.equals(item.getConfigKey())).findFirst().orElse(null);
        if (existing == null)
        {
            SysConfig created = new SysConfig();
            created.setConfigName(name);
            created.setConfigKey(key);
            created.setConfigValue(value);
            created.setConfigType("Y");
            created.setRemark("由LLM配置页面维护，请勿直接填写明文密钥");
            configService.insertConfig(created);
        }
        else
        {
            existing.setConfigName(name);
            existing.setConfigValue(value);
            existing.setConfigType("Y");
            existing.setRemark("由LLM配置页面维护，请勿直接填写明文密钥");
            configService.updateConfig(existing);
        }
    }

    private String encryptApiKey(String plainText)
    {
        ensurePersistenceSecret();
        try
        {
            byte[] iv = new byte[12];
            SECURE_RANDOM.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, encryptionKey(), new GCMParameterSpec(128, iv));
            byte[] encrypted = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));
            byte[] packed = new byte[iv.length + encrypted.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(encrypted, 0, packed, iv.length, encrypted.length);
            return CIPHER_PREFIX + Base64.getEncoder().encodeToString(packed);
        }
        catch (Exception e) { throw new IllegalStateException("API Key加密保存失败", e); }
    }

    private String decryptApiKey(String cipherText)
    {
        ensurePersistenceSecret();
        if (!cipherText.startsWith(CIPHER_PREFIX))
            throw new IllegalStateException("数据库中的LLM API Key不是受支持的密文格式，请在页面重新保存");
        try
        {
            byte[] packed = Base64.getDecoder().decode(cipherText.substring(CIPHER_PREFIX.length()));
            if (packed.length <= 28) throw new IllegalArgumentException("密文长度不正确");
            byte[] iv = Arrays.copyOfRange(packed, 0, 12);
            byte[] encrypted = Arrays.copyOfRange(packed, 12, packed.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, encryptionKey(), new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        }
        catch (Exception e) { throw new IllegalStateException("数据库中的LLM API Key解密失败，请检查持久化密钥", e); }
    }

    private SecretKeySpec encryptionKey() throws Exception
    {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(persistenceSecret.getBytes(StandardCharsets.UTF_8));
        return new SecretKeySpec(digest, "AES");
    }

    private void ensurePersistenceSecret()
    {
        if (persistenceSecret.isBlank())
            throw new IllegalStateException("未配置business.llm.persistence-secret，不能安全持久化API Key");
    }

    private String validateApiUrl(String value)
    {
        URI uri;
        try { uri = URI.create(value); }
        catch (Exception e) { throw new IllegalArgumentException("请求地址格式不正确"); }
        String scheme = uri.getScheme();
        String host = uri.getHost();
        boolean local = "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host);
        if (host == null || !("https".equalsIgnoreCase(scheme) || local && "http".equalsIgnoreCase(scheme)))
            throw new IllegalArgumentException("请求地址必须使用HTTPS（本机测试地址可使用HTTP）");
        return uri.toString();
    }

    private String validateModel(String value)
    {
        String candidate = value == null ? "" : value.trim();
        if (!candidate.matches("[A-Za-z0-9._:-]{2,120}"))
            throw new IllegalArgumentException("模型名称格式不正确");
        return candidate;
    }
}
