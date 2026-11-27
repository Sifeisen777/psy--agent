package com.psyagent.bootstrap.tool;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具执行的统一返回。
 * <p>
 * 约定：**工具不抛异常**。参数不合法、数据库不可用都返回 fail，
 * 因为工具是增强能力，失败时对话必须继续。抛异常只会被编排层吞掉，
 * 反而丢了"为什么失败"这条信息。
 *
 * @param summary 给模型看的一句话结论，会被注入系统提示
 * @param data    结构化结果，给前端 / MCP 客户端使用
 */
public record ToolResult(boolean success, String summary, Map<String, Object> data, String error) {

    public static ToolResult ok(String summary, Map<String, Object> data) {
        return new ToolResult(true, summary, data == null ? Map.of() : data, null);
    }

    public static ToolResult fail(String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("error", message);
        return new ToolResult(false, "工具执行失败：" + message, data, message);
    }
}
