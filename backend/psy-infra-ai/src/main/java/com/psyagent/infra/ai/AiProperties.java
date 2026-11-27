package com.psyagent.infra.ai;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * AI 接入配置。
 * <p>
 * 密钥统一由环境变量注入，配置文件中不保留明文。
 *
 * @param apiKey      模型 Key，为空则启用 Mock 模式
 * @param baseUrl     OpenAI 兼容端点，DeepSeek 为 https://api.deepseek.com
 * @param model       模型名
 * @param systemPrompt 全局人设
 * @param timeoutSeconds 单次调用超时（秒）
 * @param memoryMaxMessages 注入上下文的历史消息上限
 */
@ConfigurationProperties(prefix = "psy.ai")
public record AiProperties(
        String apiKey,
        String baseUrl,
        String model,
        String systemPrompt,
        Integer timeoutSeconds,
        Integer memoryMaxMessages
) {

    public AiProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "https://api.deepseek.com";
        }
        if (model == null || model.isBlank()) {
            model = "deepseek-chat";
        }
        if (systemPrompt == null || systemPrompt.isBlank()) {
            systemPrompt = """
                    你是一名温和、专业的心理支持助手。你的职责是倾听、共情、提供可执行的调节建议。
                    【必须遵守】
                    1. 不做医学诊断，不开药，不替代专业心理咨询；
                    2. 识别到自伤、自杀、伤害他人等高风险表述时，立即停止常规建议，优先给出求助渠道并提示转人工；
                    3. 回答简洁，先共情再建议，单次不超过 200 字；
                    4. 不索取身份证、住址、单位等敏感个人信息。
                    """;
        }
        if (timeoutSeconds == null || timeoutSeconds <= 0) {
            timeoutSeconds = 30;
        }
        if (memoryMaxMessages == null || memoryMaxMessages <= 0) {
            memoryMaxMessages = 20;
        }
    }

    /** Key 是否真的可用（排除占位符与空白）。 */
    public boolean keyPresent() {
        return apiKey != null
                && !apiKey.isBlank()
                && !apiKey.startsWith("sk-placeholder")
                && !apiKey.contains("${");
    }
}
