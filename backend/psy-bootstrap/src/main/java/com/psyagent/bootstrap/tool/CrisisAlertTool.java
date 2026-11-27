package com.psyagent.bootstrap.tool;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 危机告警：向所有已装配的出站通道广播一条告警。
 * <p>
 * 两个边界：
 * <p>
 * 1. **不携带对话原文**。告警里只有等级、触发原因摘要与三个 ID；
 *    原文在 t_message，复核人员按 conversationId 去看。心理数据的外发必须最小化；
 * 2. **单通道失败不影响其余**。日志通道永远在线，邮件挂了只是少一条通知，
 *    不能让整个工具返回失败、更不能让正在生成的回复被中断。
 */
@Component
public class CrisisAlertTool implements AgentTool {

    private static final Logger log = LoggerFactory.getLogger(CrisisAlertTool.class);

    private final List<AlertChannel> channels;

    public CrisisAlertTool(List<AlertChannel> channels) {
        this.channels = List.copyOf(channels);
    }

    @Override
    public String name() {
        return "crisis_alert";
    }

    @Override
    public String description() {
        return "发送一条危机告警，供后台人工复核。用于系统识别到自伤、自杀等高风险表述时。"
                + "参数只传等级与原因摘要，不要传对话原文。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> level = new LinkedHashMap<>();
        level.put("type", "string");
        level.put("enum", List.of("HIGH", "MEDIUM"));
        level.put("description", "风险等级，默认 HIGH");

        Map<String, Object> root = new LinkedHashMap<>();
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("level", level);
        props.put("reason", Map.of("type", "string", "description", "触发原因摘要，不要包含用户原话"));
        props.put("conversationId", Map.of("type", "integer", "description", "会话 ID，可选"));
        root.put("type", "object");
        root.put("properties", props);
        return root;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext context) {
        String level = ToolArgs.str(args, "level", "HIGH").toUpperCase();
        if (!level.equals("HIGH") && !level.equals("MEDIUM")) {
            level = "HIGH";
        }
        String reason = ToolArgs.str(args, "reason", "规则命中危机表述");
        // 原文可能很长，且属于敏感内容，告警里只留摘要
        if (reason.length() > 120) {
            reason = reason.substring(0, 120);
        }
        Long conversationId = context.conversationId();
        Object override = args == null ? null : args.get("conversationId");
        if (override instanceof Number number) {
            conversationId = number.longValue();
        }

        AlertChannel.AlertMessage message = new AlertChannel.AlertMessage(
                level, reason, context.userId(), conversationId, context.traceId());

        List<String> delivered = new ArrayList<>();
        List<String> failed = new ArrayList<>();
        for (AlertChannel channel : channels) {
            try {
                if (channel.send(message)) {
                    delivered.add(channel.name());
                } else {
                    failed.add(channel.name());
                }
            } catch (RuntimeException e) {
                log.warn("alert channel failed name={}", channel.name(), e);
                failed.add(channel.name());
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("level", level);
        data.put("delivered", delivered);
        data.put("failed", failed);
        data.put("traceId", context.traceId());
        data.put("conversationId", conversationId);

        if (delivered.isEmpty()) {
            return new ToolResult(false, "所有告警通道均失败，请立即检查告警配置",
                    data, "no channel delivered");
        }
        return ToolResult.ok("已发送 " + level + " 级告警，投递通道 " + delivered
                + (failed.isEmpty() ? "" : "，失败通道 " + failed), data);
    }
}
