-- 将已持久化的统一 LLM 配置切换为 DeepSeek，并清除旧方舟 API Key（必须重新填写 DeepSeek Key）
UPDATE sys_config
SET config_value = 'https://api.deepseek.com/v1/chat/completions',
    update_by = 'admin',
    update_time = NOW()
WHERE config_key = 'business.llm.apiUrl';

UPDATE sys_config
SET config_value = 'deepseek-chat',
    update_by = 'admin',
    update_time = NOW()
WHERE config_key = 'business.llm.model';

UPDATE sys_config
SET config_value = '__CLEARED__',
    update_by = 'admin',
    update_time = NOW()
WHERE config_key = 'business.llm.apiKeyEncrypted';

SELECT config_key, config_value FROM sys_config WHERE config_key LIKE 'business.llm%';
