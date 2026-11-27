package com.psyagent.bootstrap.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.psyagent.store.entity.ToolCall;
import com.psyagent.store.mapper.ToolCallMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工具注册表：所有入口共用的唯一清单。
 * <p>
 * 两个职责：
 * <p>
 * 1. **描述**：{@link #descriptors()} 直接作为 MCP tools/list 的响应，
 *    也用于编排层拼装"本轮可用工具"提示词，一份定义两处消费；
 * 2. **执行 + 审计**：任何入口的调用都经过 {@link #invoke}，统一写 t_tool_call，
 *    包含脱敏后的参数、结果摘要、耗时与成败。审计写失败只记日志，不掩盖工具结果。
 */
@Service
public class ToolRegistry {

    private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);
    private static final int MAX_JSON = 4000;

    private final Map<String, AgentTool> tools = new LinkedHashMap<>();
    private final ToolCallMapper toolCallMapper;
    private final ObjectMapper objectMapper;

    public ToolRegistry(List<AgentTool> toolBeans, ToolCallMapper toolCallMapper,
                        ObjectMapper objectMapper) {
        for (AgentTool tool : toolBeans) {
            AgentTool previous = tools.put(tool.name(), tool);
            if (previous != null) {
                throw new IllegalStateException("duplicate tool name: " + tool.name());
            }
        }
        this.toolCallMapper = toolCallMapper;
        this.objectMapper = objectMapper;
        log.info("tool registry initialized count={} names={}", tools.size(), tools.keySet());
    }

    public List<String> names() {
        return List.copyOf(tools.keySet());
    }

    /** MCP tools/list 的标准结构：name / description / inputSchema。 */
    public List<Map<String, Object>> descriptors() {
        List<Map<String, Object>> result = new ArrayList<>(tools.size());
        for (AgentTool tool : tools.values()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", tool.name());
            item.put("description", tool.description());
            item.put("inputSchema", tool.inputSchema());
            result.add(item);
        }
        return result;
    }

    /** 执行工具；未知工具与工具内部异常都转成 fail 结果，绝不抛出。 */
    public ToolResult invoke(String name, Map<String, Object> args, ToolContext context) {
        long start = System.currentTimeMillis();
        AgentTool tool = tools.get(name);
        ToolResult result;
        if (tool == null) {
            result = ToolResult.fail("未知工具 " + name + "，可用：" + names());
        } else {
            try {
                result = tool.execute(args, context);
            } catch (RuntimeException e) {
                log.warn("tool threw unexpectedly name={}", name, e);
                result = ToolResult.fail(name + " 执行异常：" + e.getMessage());
            }
        }
        audit(name, args, context, result, (int) (System.currentTimeMillis() - start));
        return result;
    }

    private void audit(String name, Map<String, Object> args, ToolContext context,
                       ToolResult result, int costMs) {
        try {
            ToolCall record = new ToolCall();
            record.setTraceId(context.traceId());
            record.setUserId(context.userId());
            record.setConversationId(context.conversationId());
            record.setToolName(name);
            record.setSource(context.source());
            record.setArgumentsJson(toJson(ToolArgs.masked(args)));
            record.setResultJson(toJson(result.data()));
            record.setSuccess(result.success() ? 1 : 0);
            record.setErrorMsg(truncate(result.error(), 250));
            record.setCostMs(costMs);
            toolCallMapper.insert(record);
        } catch (RuntimeException e) {
            // 审计失败不能改变工具结果：可观测性不能反过来影响业务链路
            log.warn("tool audit failed name={}", name, e);
        }
    }

    private String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return truncate(objectMapper.writeValueAsString(value), MAX_JSON);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"serialize failed\"}";
        }
    }

    private static String truncate(String text, int max) {
        if (text == null || text.length() <= max) {
            return text;
        }
        return text.substring(0, max) + "...(truncated)";
    }
}
