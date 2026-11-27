package com.psyagent.bootstrap.tool;

import java.util.Map;

/**
 * 一个可被编排节点、REST 或 MCP 客户端调用的工具。
 * <p>
 * 刻意做成"描述 + JSON Schema + 执行"三件套：
 * 描述和 Schema 既给内部提示词用，也直接生成 MCP 的 tools/list 响应，
 * **不需要维护两份工具清单**。这是自研 MCP 端点能保持很小的关键。
 */
public interface AgentTool {

    /** 全局唯一名，同时是 MCP 的 tools/call 名称。 */
    String name();

    /** 给模型看的用途说明，决定它会不会选这个工具。 */
    String description();

    /** 标准 JSON Schema，MCP 客户端据此构造参数。 */
    Map<String, Object> inputSchema();

    /**
     * 执行。实现方必须捕获自身异常并返回 {@link ToolResult#fail(String)}，
     * 不得向外抛出。
     */
    ToolResult execute(Map<String, Object> args, ToolContext context);
}
