package com.psyagent.bootstrap.tool;

import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 返回量表题干。
 * <p>
 * 与 {@link ScaleScoreTool} 分开是两个职责：取题是只读、无副作用；
 * 计分会落库并可能触发风险提示。合成一个工具会让"看一眼题目"也产生一条记录。
 * <p>
 * 题干按公开筛查量表的通用表述给出，计分规则与分级在计分工具里保持一致。
 */
@Component
public class ScaleQuestionsTool implements AgentTool {

    private static final List<String> PHQ9_ITEMS = List.of(
            "做事时提不起劲或没有兴趣",
            "感到心情低落、沮丧或绝望",
            "入睡困难、睡不安稳，或睡得太多",
            "感到疲倦或没有活力",
            "食欲不振，或吃得过多",
            "觉得自己很糟、很失败，或让家人失望",
            "难以集中注意力，例如看书或看电视时",
            "动作说话明显变慢，或比平时更烦躁坐不住",
            "觉得不如死掉，或有伤害自己的念头"
    );

    private static final List<String> GAD7_ITEMS = List.of(
            "感觉紧张、焦虑或急切",
            "无法停止或控制担忧",
            "对各种各样的事情担忧过多",
            "很难放松下来",
            "因为不安而难以静坐",
            "容易烦恼或急躁",
            "觉得似乎会有可怕的事情发生"
    );

    private static final List<String> OPTIONS = List.of(
            "0 = 完全不会", "1 = 有几天", "2 = 一半以上天数", "3 = 几乎每天"
    );

    @Override
    public String name() {
        return "scale_questions";
    }

    @Override
    public String description() {
        return "获取 PHQ-9 或 GAD-7 自评量表的题目与计分选项。用户想做量表、"
                + "需要题目，或询问量表怎么答时使用。只返回题目，不产生记录。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> scale = new LinkedHashMap<>();
        scale.put("type", "string");
        scale.put("enum", List.of("PHQ9", "GAD7"));
        scale.put("description", "量表代号，默认 PHQ9");
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", Map.of("scale", scale));
        return root;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext context) {
        String code = ToolArgs.str(args, "scale", "PHQ9").toUpperCase();
        boolean phq9 = !"GAD7".equals(code);
        List<String> items = phq9 ? PHQ9_ITEMS : GAD7_ITEMS;

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scale", phq9 ? "PHQ9" : "GAD7");
        data.put("title", phq9 ? "PHQ-9 抑郁筛查（过去两周）" : "GAD-7 焦虑筛查（过去两周）");
        data.put("options", OPTIONS);
        data.put("itemCount", items.size());
        data.put("questions", items);
        data.put("answerFormat", "按题目顺序回复 " + items.size() + " 个 0-3 的数字，用逗号分隔");
        data.put("disclaimer", "自评量表用于筛查，不是诊断结论");

        return ToolResult.ok("已取出 " + (phq9 ? "PHQ-9" : "GAD-7") + " 共 " + items.size()
                + " 道题，每题按过去两周的出现频率选 0-3 分", data);
    }
}
