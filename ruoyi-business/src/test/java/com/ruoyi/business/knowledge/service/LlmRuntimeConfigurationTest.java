package com.ruoyi.business.knowledge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import com.ruoyi.system.domain.SysConfig;
import com.ruoyi.system.service.ISysConfigService;
import org.junit.jupiter.api.Test;

class LlmRuntimeConfigurationTest
{
    @Test
    void savedConfigurationSurvivesNewServiceInstanceAndApiKeyIsEncrypted()
    {
        Map<String, SysConfig> database = new LinkedHashMap<>();
        AtomicLong ids = new AtomicLong(1);
        ISysConfigService service = mock(ISysConfigService.class);
        when(service.selectConfigByKey(any())).thenAnswer(invocation -> {
            SysConfig row = database.get(invocation.getArgument(0));
            return row == null ? "" : row.getConfigValue();
        });
        when(service.selectConfigList(any())).thenAnswer(invocation -> {
            SysConfig query = invocation.getArgument(0);
            SysConfig row = database.get(query.getConfigKey());
            return row == null ? new ArrayList<>() : new ArrayList<>(List.of(row));
        });
        when(service.insertConfig(any())).thenAnswer(invocation -> {
            SysConfig row = invocation.getArgument(0);
            row.setConfigId(ids.getAndIncrement());
            database.put(row.getConfigKey(), row);
            return 1;
        });
        when(service.updateConfig(any())).thenAnswer(invocation -> {
            SysConfig row = invocation.getArgument(0);
            database.put(row.getConfigKey(), row);
            return 1;
        });

        String secret = "stable-persistence-secret-for-test";
        LlmRuntimeConfiguration first = new LlmRuntimeConfiguration(
            "https://default.example.com/v1/chat", "default-model", "", secret, service);
        first.update("https://ark.example.com/v3/chat/completions", "saved-model", "secret-api-key", false);

        String storedKey = database.get("business.llm.apiKeyEncrypted").getConfigValue();
        assertTrue(storedKey.startsWith("v1:"));
        assertFalse(storedKey.contains("secret-api-key"));

        LlmRuntimeConfiguration restarted = new LlmRuntimeConfiguration(
            "https://fallback.example.com/v1/chat", "fallback-model", "", secret, service);
        restarted.loadPersistedConfiguration();
        assertEquals("https://ark.example.com/v3/chat/completions", restarted.getApiUrl());
        assertEquals("saved-model", restarted.getModel());
        assertEquals("secret-api-key", restarted.getApiKey());
        assertEquals("MYSQL_ENCRYPTED", restarted.view().get("apiKeyStorage"));
    }
}
