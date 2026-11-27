package com.psyagent.bootstrap.tool;

import com.psyagent.store.entity.ScaleRecord;
import com.psyagent.store.mapper.ScaleRecordMapper;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 量表计分：PHQ-9（抑郁筛查，9 题）/ GAD-7（焦虑筛查，7 题），每题 0-3 分。
 * <p>
 * 两个刻意的安全约束：
 * <p>
 * 1. **PHQ-9 第 9 题是自伤意念**，只要 >0 就强制打风险标记并附求助热线。
 *    量表总分可能很低（其他题全 0），但这一题不能平均分掉——这是筛查工具的底线；
 * 2. 结果一律写明"筛查不等于诊断"，避免用户拿分数给自己下结论。
 * <p>
 * 工具只做确定性计算，不调模型：分数算错比模型答得不好严重得多。
 */
@Component
public class ScaleScoreTool implements AgentTool {

    private static final String PHQ9 = "PHQ9";
    private static final String GAD7 = "GAD7";
    private static final String HOTLINE = "全国心理援助热线 12356";

    private final ScaleRecordMapper mapper;

    public ScaleScoreTool(ScaleRecordMapper mapper) {
        this.mapper = mapper;
    }

    @Override
    public String name() {
        return "scale_score";
    }

    @Override
    public String description() {
        return "计算心理筛查量表得分。支持 PHQ-9（9 题抑郁）与 GAD-7（7 题焦虑），"
                + "返回总分、分级与建议。当用户提交逐题作答或要求做量表时使用。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> scale = new LinkedHashMap<>();
        scale.put("type", "string");
        scale.put("enum", List.of(PHQ9, GAD7));
        scale.put("description", "量表代号，默认 PHQ9");

        Map<String, Object> answers = new LinkedHashMap<>();
        answers.put("type", "array");
        answers.put("items", Map.of("type", "integer", "minimum", 0, "maximum", 3));
        answers.put("description", "逐题作答，0-3 分；也接受 \"0,1,2\" 形式的逗号分隔字符串");

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", Map.of("scale", scale, "answers", answers));
        root.put("required", List.of("answers"));
        return root;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext context) {
        String code = ToolArgs.str(args, "scale", PHQ9).toUpperCase();
        int expected = switch (code) {
            case PHQ9 -> 9;
            case GAD7 -> 7;
            default -> 0;
        };
        if (expected == 0) {
            return ToolResult.fail("不支持的量表 " + code + "，可选 PHQ9 / GAD7");
        }

        List<Integer> answers = ToolArgs.intList(args, "answers");
        if (answers.size() != expected) {
            return ToolResult.fail(code + " 需要 " + expected + " 题作答，实际收到 " + answers.size());
        }
        for (int i = 0; i < answers.size(); i++) {
            int value = answers.get(i);
            if (value < 0 || value > 3) {
                return ToolResult.fail("第 " + (i + 1) + " 题分值 " + value + " 超出 0-3");
            }
        }

        int score = answers.stream().mapToInt(Integer::intValue).sum();
        boolean selfHarmFlag = PHQ9.equals(code) && answers.get(8) > 0;
        String severity = severity(code, score);
        if (selfHarmFlag) {
            severity = "MODERATE";
        }
        String suggestion = selfHarmFlag
                ? "第 9 题提示存在自伤念头，请立即联系 " + HOTLINE + "，紧急情况拨打 120；筛查结果不能替代专业评估"
                : suggestion(code, severity);

        ScaleRecord record = new ScaleRecord();
        record.setUserId(context.userId() == null ? 0L : context.userId());
        record.setConversationId(context.conversationId());
        record.setScaleCode(code);
        record.setAnswers(answers.toString().replaceAll("[\\[\\]\\s]", ""));
        record.setScore(score);
        record.setSeverity(severity);
        record.setSuggestion(suggestion);
        record.setTraceId(context.traceId());
        try {
            mapper.insert(record);
        } catch (RuntimeException e) {
            // 计分结果已经算出来了，不能因为落库失败就对用户说"做不了"
            return ToolResult.ok(summary(code, score, severity, selfHarmFlag),
                    data(code, answers, score, severity, suggestion, selfHarmFlag, null));
        }
        return ToolResult.ok(summary(code, score, severity, selfHarmFlag),
                data(code, answers, score, severity, suggestion, selfHarmFlag, record.getId()));
    }

    private static String severity(String code, int score) {
        if (PHQ9.equals(code)) {
            if (score <= 4) return "MINIMAL";
            if (score <= 9) return "MILD";
            if (score <= 14) return "MODERATE";
            if (score <= 19) return "MODERATELY_SEVERE";
            return "SEVERE";
        }
        if (score <= 4) return "MINIMAL";
        if (score <= 9) return "MILD";
        if (score <= 14) return "MODERATE";
        return "SEVERE";
    }

    private static String suggestion(String code, String severity) {
        return switch (severity) {
            case "MINIMAL" -> "当前得分在极轻范围，保持规律作息与社交即可；筛查结果不等于诊断";
            case "MILD" -> "建议记录两周情绪变化，配合规律运动；如持续困扰可寻求专业评估";
            case "MODERATE" -> "建议尽快预约心理咨询或前往精神科做专业评估，不要自行下结论";
            default -> "得分较高，建议尽快到正规医疗机构评估，并告知一位可信任的人陪同；"
                    + "如有危机感受请联系 " + HOTLINE;
        };
    }

    private static String summary(String code, int score, String severity, boolean selfHarmFlag) {
        String base = code + " 总分 " + score + "，分级 " + severity
                + "；结果仅为筛查，不是诊断。";
        return selfHarmFlag ? base + " 注意：第 9 题提示自伤念头，须给出 " + HOTLINE + "。" : base;
    }

    private static Map<String, Object> data(String code, List<Integer> answers, int score,
                                            String severity, String suggestion,
                                            boolean selfHarmFlag, Long recordId) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scale", code);
        data.put("maxScore", PHQ9.equals(code) ? 27 : 21);
        data.put("answers", answers);
        data.put("score", score);
        data.put("severity", severity);
        data.put("suggestion", suggestion);
        data.put("selfHarmFlag", selfHarmFlag);
        data.put("riskHotline", selfHarmFlag ? "12356" : null);
        data.put("recordId", recordId);
        return data;
    }
}
