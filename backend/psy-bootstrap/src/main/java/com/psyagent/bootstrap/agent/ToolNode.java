package com.psyagent.bootstrap.agent;

import com.psyagent.bootstrap.tool.ToolContext;
import com.psyagent.bootstrap.tool.ToolRegistry;
import com.psyagent.bootstrap.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 工具编排节点：识别本轮意图并调用工具，把真实结果注入系统提示。
 * <p>
 * 为什么不让模型通过 function calling 自己决定：
 * <p>
 * 1. **流式体验**：工具调用会打断逐字输出，用户会看到一段空白后突然换话题；
 * 2. **可测性**：Mock 模式没有真实模型，如果工具只能由模型触发，整条链路在无 Key 环境下不可验证；
 * 3. **可解释**：规则命中哪个工具写进轨迹，出问题能复盘，而不是"模型这次没调"。
 * <p>
 * 这不等于"模型不能调工具"：同一批工具通过 MCP 的 tools/call 暴露给任意模型客户端，
 * 那是协议层面的自主调用。这里是**服务内部**的确定性编排，两者共用一份注册表。
 * <p>
 * 槽位抽取用规则是刻意的简化：生产环境应交给模型做结构化抽取，
 * 但那会让最小可运行链路依赖密钥。规则版本能在任何环境下跑通并被评测集验证。
 */
@Component
@Order(40)
public class ToolNode implements AgentNode {

    private static final Logger log = LoggerFactory.getLogger(ToolNode.class);

    private static final List<String> SCALE_WORDS = List.of(
            "量表", "自评", "测一下", "测测", "问卷", "PHQ", "GAD", "做道题", "评分"
    );
    private static final List<String> STATS_WORDS = List.of(
            "统计", "多少轮", "运行状况", "运行情况", "首字", "延迟", "p95", "p50", "看板", "调用次数"
    );
    private static final List<String> APPOINTMENT_WORDS = List.of(
            "预约", "约咨询", "安排咨询", "报名咨询"
    );

    /** 7 或 9 个逗号分隔的 0-3 分值。 */
    private static final Pattern ANSWERS = Pattern.compile(
            "(?:^|[^0-9])([0-3](?:\\s*[,，]\\s*[0-3]){6,8})(?:$|[^0-9])");
    private static final Pattern CONTACT = Pattern.compile(
            "1[3-9]\\d{9}|[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}");
    private static final Pattern TIME = Pattern.compile(
            "周[一二三四五六日末](?:上午|下午|晚上)?|明天|后天|大后天|"
                    + "\\d{1,2}月\\d{1,2}日(?:上午|下午|晚上)?|(?:上午|下午|晚上)\\d{0,2}点?");

    private final ToolRegistry registry;

    public ToolNode(ToolRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return "工具编排";
    }

    @Override
    public boolean supports(AgentContext context) {
        return context.getRoute() == AgentRoute.CRISIS || intent(context.getMessage()) != Intent.NONE;
    }

    @Override
    public void apply(AgentContext context) {
        ToolContext toolContext = ToolContext.agent(
                context.getUserId(), context.getConversationId(), context.getTraceId());

        // 危机告警是自动的：不能指望模型"想起来"发告警
        if (context.getRoute() == AgentRoute.CRISIS) {
            Map<String, Object> args = new LinkedHashMap<>();
            args.put("level", "HIGH");
            args.put("reason", "分诊规则命中危机表述");
            invoke(context, toolContext, "crisis_alert", args);
        }

        String message = context.getMessage();
        switch (intent(message)) {
            case STATS -> invoke(context, toolContext, "chat_stats", Map.of("hours", 24));
            case SCALE_ANSWERS -> {
                List<Integer> answers = extractAnswers(message);
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("scale", answers.size() == 7 ? "GAD7" : "PHQ9");
                args.put("answers", answers);
                invoke(context, toolContext, "scale_score", args);
            }
            case SCALE -> {
                Map<String, Object> args = new LinkedHashMap<>();
                if (message.contains("GAD") || message.contains("焦虑")) {
                    args.put("scale", "GAD7");
                }
                invoke(context, toolContext, "scale_questions", args);
                context.addGuidance("用户想做量表，请把题目与 0-3 的选项说明转述给他，"
                        + "并提示按题目顺序回复一串数字即可计分。不要代替用户作答。");
            }
            case APPOINTMENT -> {
                Matcher contact = CONTACT.matcher(message);
                if (contact.find()) {
                    Matcher time = TIME.matcher(message);
                    Map<String, Object> args = new LinkedHashMap<>();
                    args.put("contact", contact.group());
                    args.put("preferredTime", time.find() ? time.group() : "待确认");
                    args.put("topic", "用户对话中提出的预约请求");
                    invoke(context, toolContext, "create_appointment", args);
                } else {
                    context.addGuidance("用户想预约咨询，但还没留下联系方式。"
                            + "请温和地请他留一个手机号或邮箱以及期望时间，"
                            + "说明后续会有工作人员确认；不要承诺具体日程。");
                }
            }
            case NONE -> {
                // 只有 CRISIS 会走到这里，告警已在上面执行
            }
        }
    }

    private void invoke(AgentContext context, ToolContext toolContext,
                        String name, Map<String, Object> args) {
        long start = System.currentTimeMillis();
        ToolResult result = registry.invoke(name, args, toolContext);
        int cost = (int) (System.currentTimeMillis() - start);
        context.addToolExecution(name, result.summary(), result.success(), cost);
        if (!result.success()) {
            log.warn("tool failed in chain name={} traceId={} error={}",
                    name, context.getTraceId(), result.error());
        }
    }

    @Override
    public String detail(AgentContext context) {
        int count = context.getToolExecutions().size();
        if (count == 0) {
            return "待补充信息";
        }
        return count + " 个：" + context.toolNames();
    }

    private enum Intent {
        NONE, STATS, SCALE_ANSWERS, SCALE, APPOINTMENT
    }

    private static Intent intent(String message) {
        if (message == null || message.isBlank()) {
            return Intent.NONE;
        }
        if (SCALE_WORDS.stream().anyMatch(message::contains)) {
            return extractAnswers(message).isEmpty() ? Intent.SCALE : Intent.SCALE_ANSWERS;
        }
        if (STATS_WORDS.stream().anyMatch(message::contains)) {
            return Intent.STATS;
        }
        if (APPOINTMENT_WORDS.stream().anyMatch(message::contains)) {
            return Intent.APPOINTMENT;
        }
        return Intent.NONE;
    }

    private static List<Integer> extractAnswers(String message) {
        Matcher matcher = ANSWERS.matcher(message);
        if (!matcher.find()) {
            return List.of();
        }
        String[] parts = matcher.group(1).split("[,，]");
        // 只接受恰好 7 题（GAD-7）或 9 题（PHQ-9）的作答
        if (parts.length != 7 && parts.length != 9) {
            return List.of();
        }
        java.util.List<Integer> result = new java.util.ArrayList<>(parts.length);
        for (String part : parts) {
            result.add(Integer.parseInt(part.trim()));
        }
        return result;
    }
}
