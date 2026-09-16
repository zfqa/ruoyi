package com.ruoyi.business.knowledge.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.Test;

class LlmEndpointNormalizerTest
{
    @Test
    void deepseekCompletionsAndBaseUrlsAreNormalized()
    {
        assertEquals("https://api.deepseek.com/v1/chat/completions",
            LlmEndpointNormalizer.completionsUrl("https://api.deepseek.com/v1/chat/completions"));
        assertEquals("https://api.deepseek.com/v1/chat/completions",
            LlmEndpointNormalizer.completionsUrl("https://api.deepseek.com/v1"));
        assertEquals("https://api.deepseek.com/v1/chat/completions",
            LlmEndpointNormalizer.completionsUrl("https://api.deepseek.com"));
        assertEquals("https://api.deepseek.com/v1",
            LlmEndpointNormalizer.openAiBaseUrl("https://api.deepseek.com/v1/chat/completions"));
        assertEquals("https://api.deepseek.com/v1",
            LlmEndpointNormalizer.openAiBaseUrl("https://api.deepseek.com/v1"));
    }

    @Test
    void arkStyleUrlsRemainCompatible()
    {
        String ark = "https://ark.cn-beijing.volces.com/api/v3/chat/completions";
        assertEquals(ark, LlmEndpointNormalizer.completionsUrl(ark));
        assertEquals("https://ark.cn-beijing.volces.com/api/v3",
            LlmEndpointNormalizer.openAiBaseUrl(ark));
        assertEquals(ark,
            LlmEndpointNormalizer.completionsUrl("https://ark.cn-beijing.volces.com/api/v3"));
    }
}
