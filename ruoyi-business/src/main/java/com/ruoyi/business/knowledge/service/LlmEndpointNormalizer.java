package com.ruoyi.business.knowledge.service;

/**
 * 统一 LLM 地址规范化：库内保存完整 chat/completions URL；
 * 下发给 LangChain/OpenAI SDK 时转为 base_url（去掉 /chat/completions）。
 */
public final class LlmEndpointNormalizer
{
    private LlmEndpointNormalizer()
    {
    }

    /** 规范为可直接 POST 的 Chat Completions 地址。 */
    public static String completionsUrl(String raw)
    {
        String value = trimTrailingSlash(raw == null ? "" : raw.trim());
        if (value.isEmpty())
        {
            return value;
        }
        if (value.endsWith("/chat/completions"))
        {
            return value;
        }
        // 已是 OpenAI 兼容 root，如 https://api.deepseek.com/v1 或 .../api/v3
        if (looksLikeOpenAiRoot(value))
        {
            return value + "/chat/completions";
        }
        // 仅域名，如 https://api.deepseek.com
        if (value.matches("(?i)https?://[^/]+"))
        {
            return value + "/v1/chat/completions";
        }
        return value + "/chat/completions";
    }

    /** 规范为 OpenAI SDK / LangChain 使用的 base_url。 */
    public static String openAiBaseUrl(String raw)
    {
        String value = trimTrailingSlash(raw == null ? "" : raw.trim());
        if (value.isEmpty())
        {
            return value;
        }
        if (value.endsWith("/chat/completions"))
        {
            value = value.substring(0, value.length() - "/chat/completions".length());
            return trimTrailingSlash(value);
        }
        if (looksLikeOpenAiRoot(value))
        {
            return value;
        }
        if (value.matches("(?i)https?://[^/]+"))
        {
            return value + "/v1";
        }
        return value;
    }

    private static boolean looksLikeOpenAiRoot(String value)
    {
        return value.matches("(?i).*/v\\d+$") || value.matches("(?i).*/api/v\\d+$");
    }

    private static String trimTrailingSlash(String value)
    {
        String result = value;
        while (result.endsWith("/"))
        {
            result = result.substring(0, result.length() - 1);
        }
        return result;
    }
}
