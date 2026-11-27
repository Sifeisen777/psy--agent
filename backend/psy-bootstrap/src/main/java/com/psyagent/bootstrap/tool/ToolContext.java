package com.psyagent.bootstrap.tool;

/**
 * 一次工具调用的调用方身份与链路信息。
 * <p>
 * 工具本身不读 SecurityContext：MCP 客户端和内部编排节点的身份来源完全不同，
 * 硬编码从线程上下文取会让工具在 MCP 场景下永远拿不到 userId。
 * 由入口显式构造，工具只消费。
 *
 * @param source AGENT（编排节点）/ MCP / REST
 */
public record ToolContext(Long userId, Long conversationId, String traceId, String source) {

    public static final String SOURCE_AGENT = "AGENT";
    public static final String SOURCE_MCP = "MCP";
    public static final String SOURCE_REST = "REST";

    public static ToolContext agent(Long userId, Long conversationId, String traceId) {
        return new ToolContext(userId, conversationId, traceId, SOURCE_AGENT);
    }
}
