package com.psyagent.infra.ai;

import java.util.List;

/**
 * 基于关键词的情绪判定，作为模型评估失败时的兜底。
 * <p>
 * 这里的取舍很明确：**宁可误报，不可漏报**。命中"自杀""不想活"这类表述时直接判 HIGH，
 * 即使上下文其实是在讨论别人的事——误报的代价是多一次提示，漏报的代价可能是生命。
 * <p>
 * 规则只覆盖少量高确定性词汇，其余交给模型。它不追求准确，只保证"模型挂掉时系统仍有反应"。
 */
public final class RuleBasedEmotionJudge {

    /** 高风险表述：出现即判 HIGH，优先级最高。 */
    private static final List<String> HIGH_RISK = List.of(
            "自杀", "自残", "轻生", "不想活", "活不下去", "活着没意思", "结束生命", "了结自己",
            "割腕", "跳楼", "安眠药", "想死", "死了算了", "伤害自己"
    );

    /** 中度风险表述：持续的情绪耗竭与无望感。 */
    private static final List<String> MEDIUM_RISK = List.of(
            "绝望", "没有意义", "撑不住", "崩溃", "想消失", "没人需要我", "扛不住", "熬不下去"
    );

    /** 情绪词表，数组顺序即优先级，命中即停。 */
    private static final String[][] EMOTIONS = {
            {"焦虑", "焦虑", "紧张", "心慌", "睡不着", "失眠", "担心", "压力"},
            {"低落", "难过", "低落", "抑郁", "没劲", "提不起", "失落"},
            {"愤怒", "生气", "愤怒", "烦死", "气死", "恼火"},
            {"孤独", "孤独", "一个人", "没人懂", "没人理"}
    };

    private RuleBasedEmotionJudge() {
    }

    public static EmotionAssessment judge(String userMessage) {
        String text = userMessage == null ? "" : userMessage;

        String riskLevel = EmotionAssessment.RISK_LOW;
        String reason = "未发现明确的风险表述";
        if (containsAny(text, HIGH_RISK)) {
            riskLevel = EmotionAssessment.RISK_HIGH;
            reason = "包含自伤或自杀相关表述";
        } else if (containsAny(text, MEDIUM_RISK)) {
            riskLevel = EmotionAssessment.RISK_MEDIUM;
            reason = "包含明显的无望或耗竭表述";
        }

        String emotion = "未知";
        for (String[] row : EMOTIONS) {
            if (containsAny(text, List.of(row).subList(1, row.length))) {
                emotion = row[0];
                break;
            }
        }

        int intensity = switch (riskLevel) {
            case EmotionAssessment.RISK_HIGH -> 90;
            case EmotionAssessment.RISK_MEDIUM -> 70;
            default -> containsAny(text, List.of("很", "非常", "特别", "太")) ? 60 : 40;
        };

        String suggestion = EmotionAssessment.RISK_HIGH.equals(riskLevel)
                ? "请优先联系身边可信任的人陪同，并拨打全国心理援助热线 12356。"
                : "可以先做几次缓慢的深呼吸，把当下的感受写下来，再决定下一步。";

        return new EmotionAssessment(emotion, intensity, riskLevel, reason, suggestion, "RULE").normalized();
    }

    private static boolean containsAny(String text, List<String> keywords) {
        return keywords.stream().anyMatch(text::contains);
    }
}
