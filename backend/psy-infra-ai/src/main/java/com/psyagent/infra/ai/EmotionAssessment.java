package com.psyagent.infra.ai;

/**
 * 情绪评估结果。
 * <p>
 * 由模型以 JSON 形式输出，用于驱动后续的分流：风险等级决定是否进入危机干预，
 * 情绪与强度决定推荐哪类调节方案。之所以要求结构化，是因为自由文本无法稳定判断分支——
 * 让模型"描述一下情绪"得到的是一段话，代码无从消费。
 *
 * @param emotion    主要情绪，如 焦虑 / 低落 / 愤怒 / 平静
 * @param intensity  强度 0–100，数值越大越强烈
 * @param riskLevel  NONE / LOW / MEDIUM / HIGH
 * @param reason     判定依据，简短引用用户原话中的关键表述
 * @param suggestion 一句可执行的调节建议
 * @param source     结果来源：MODEL 模型判定 / RULE 规则兜底 / MOCK 降级模式，由代码赋值，不依赖模型输出
 */
public record EmotionAssessment(
        String emotion,
        Integer intensity,
        String riskLevel,
        String reason,
        String suggestion,
        String source
) {

    public static final String RISK_NONE = "NONE";
    public static final String RISK_LOW = "LOW";
    public static final String RISK_MEDIUM = "MEDIUM";
    public static final String RISK_HIGH = "HIGH";

    private static final int REASON_MAX = 120;
    private static final int SUGGESTION_MAX = 200;

    /**
     * 归一化：模型输出不可信，字段可能缺失、越界或拼写不规范，统一在此收敛。
     * 调用方拿到的对象可以直接入库与展示，不必再判空。
     */
    public EmotionAssessment normalized() {
        String normalizedEmotion = blankToDefault(emotion, "未知");
        int normalizedIntensity = intensity == null ? 50 : Math.max(0, Math.min(100, intensity));
        String normalizedRisk = normalizeRisk(riskLevel);
        String normalizedReason = truncate(blankToDefault(reason, "未提供判定依据"), REASON_MAX);
        String normalizedSuggestion = truncate(blankToDefault(suggestion, "可以先做几次缓慢的深呼吸，让身体放松下来。"),
                SUGGESTION_MAX);
        return new EmotionAssessment(normalizedEmotion, normalizedIntensity, normalizedRisk,
                normalizedReason, normalizedSuggestion, source);
    }

    public EmotionAssessment withSource(String newSource) {
        return new EmotionAssessment(emotion, intensity, riskLevel, reason, suggestion, newSource);
    }

    /** 是否需要转入人工或展示危机干预资源。 */
    public boolean highRisk() {
        return RISK_HIGH.equals(normalizeRisk(riskLevel));
    }

    public boolean needsAttention() {
        String risk = normalizeRisk(riskLevel);
        return RISK_HIGH.equals(risk) || RISK_MEDIUM.equals(risk);
    }

    private static String normalizeRisk(String raw) {
        if (raw == null) {
            return RISK_LOW;
        }
        String upper = raw.trim().toUpperCase();
        return switch (upper) {
            case RISK_NONE, "无", "NONE_RISK" -> RISK_NONE;
            case RISK_HIGH, "高", "严重", "CRITICAL" -> RISK_HIGH;
            case RISK_MEDIUM, "中", "MODERATE" -> RISK_MEDIUM;
            default -> RISK_LOW;
        };
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static String truncate(String value, int max) {
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }
}
