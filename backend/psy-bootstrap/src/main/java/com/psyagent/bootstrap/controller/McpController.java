package com.psyagent.bootstrap.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.psyagent.bootstrap.observability.TraceContext;
import com.psyagent.bootstrap.tool.ToolContext;
import com.psyagent.bootstrap.tool.ToolRegistry;
import com.psyagent.bootstrap.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * MCP（Model Context Protocol）Streamable HTTP 端点。
 * <p>
 * 只实现协议里真正被用到的四个方法：initialize / tools/list / tools/call / ping，
 * 加一个 notifications/initialized 通知。没有引入 MCP SDK，理由和编排层一样：
 * 协议本身是一层 JSON-RPC，200 行能写完，换来的是零版本对齐成本和完全可读的调用链。
 * 面试时能讲清 tools/list 与 tools/call 的报文，比"我加了个 starter"更有说服力。
 * <p>
 * 关键设计：**与编排层共用 {@link ToolRegistry}**。
 * 工具只有一份定义，MCP 客户端看到的清单和内部节点调用的清单永远一致，
 * 不会出现"MCP 里能调、对话里没有"的漂移。
 * <p>
 * MCP 客户端不带 JWT，因此用独立的 {@code psy.mcp.access-token} 校验，
 * 支持 {@code X-MCP-Token} 或 {@code Authorization: Bearer}。
 */
@RestController
@RequestMapping("/mcp")
public class McpController {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);
    private static final String PROTOCOL_VERSION = "2025-06-18";

    private final ToolRegistry registry;
    private final ObjectMapper objectMapper;
    private final String accessToken;

    public McpController(ToolRegistry registry, ObjectMapper objectMapper,
                         @Value("${psy.mcp.access-token:}") String accessToken) {
        this.registry = registry;
        this.objectMapper = objectMapper;
        this.accessToken = accessToken;
    }

    /**
     * 请求体收 String 而不是 JsonNode，是为了自己解析。
     * <p>
     * 交给 Spring 反序列化的话，报文不合法会抛 {@code HttpMessageNotReadableException}，
     * 异常处理器只能给出 HTTP 层面的错误，JSON-RPC 客户端拿到的是 500 而不是约定的
     * {@code -32700 parse error}。协议实现的错误必须符合协议，不能返回框架的默认行为。
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<?> handle(@RequestBody(required = false) String raw,
                                    @RequestHeader(value = "X-MCP-Token", required = false) String tokenHeader,
                                    @RequestHeader(value = "Authorization", required = false) String authorization) {
        if (!authorized(tokenHeader, authorization)) {
            log.warn("mcp auth rejected, invalid access token");
            return ResponseEntity.status(401)
                    .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                    .body(error(null, -32001, "invalid MCP access token"));
        }

        JsonNode body;
        try {
            body = raw == null || raw.isBlank() ? null : objectMapper.readTree(raw);
        } catch (Exception e) {
            log.warn("mcp parse error: {}", e.getMessage());
            return ResponseEntity.ok()
                    .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                    .body(error(null, -32700, "parse error"));
        }
        if (body == null) {
            return ResponseEntity.ok()
                    .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                    .body(error(null, -32600, "invalid request: empty body"));
        }

        // 批量请求：逐个处理，通知类不产生响应（JSON-RPC 规定）
        if (body.isArray()) {
            List<Object> results = new ArrayList<>();
            for (JsonNode node : body) {
                Object result = dispatch(node);
                if (result != null) {
                    results.add(result);
                }
            }
            return ResponseEntity.ok()
                    .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                    .header("Mcp-Session-Id", UUID.randomUUID().toString())
                    .body(results);
        }

        Object result = dispatch(body);
        if (result == null) {
            // 通知（无 id）：按协议返回 202 且不带响应体
            return ResponseEntity.accepted()
                    .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                    .build();
        }
        return ResponseEntity.ok()
                .header("MCP-Protocol-Version", PROTOCOL_VERSION)
                .header("Mcp-Session-Id", UUID.randomUUID().toString())
                .body(result);
    }

    /** 协议允许 GET 用于服务端主动推送的 SSE 流；本项目无服务端推送，明确返回 405。 */
    @GetMapping
    public ResponseEntity<Map<String, Object>> get() {
        return ResponseEntity.status(405).body(error(null, -32600, "GET /mcp not supported, use POST"));
    }

    private Object dispatch(JsonNode request) {
        if (request == null || !request.isObject()) {
            return error(null, -32600, "invalid request");
        }
        JsonNode idNode = request.get("id");
        Object id = idNode == null || idNode.isNull() ? null : objectMapper.convertValue(idNode, Object.class);
        String method = request.path("method").asText("");
        JsonNode params = request.get("params");

        // 通知：客户端不期待响应
        if (id == null) {
            if (!method.startsWith("notifications/")) {
                log.debug("mcp notification received method={}", method);
            }
            return null;
        }

        return switch (method) {
            case "initialize" -> success(id, initializeResult());
            case "ping" -> success(id, Map.of());
            case "tools/list" -> success(id, Map.of("tools", registry.descriptors()));
            case "tools/call" -> callTool(id, params);
            case "resources/list" -> success(id, Map.of("resources", List.of()));
            case "prompts/list" -> success(id, Map.of("prompts", List.of()));
            default -> error(id, -32601, "method not found: " + method);
        };
    }

    private Object callTool(Object id, JsonNode params) {
        String name = params == null ? "" : params.path("name").asText("");
        if (name.isBlank()) {
            return error(id, -32602, "tools/call requires params.name");
        }
        Map<String, Object> args;
        try {
            JsonNode arguments = params == null ? null : params.get("arguments");
            args = arguments == null || arguments.isNull()
                    ? Map.of()
                    : objectMapper.convertValue(arguments, new TypeReference<Map<String, Object>>() {
            });
        } catch (RuntimeException e) {
            return error(id, -32602, "invalid params.arguments: " + e.getMessage());
        }

        Long userId = longFrom(args, "userId", 0L);
        Long conversationId = longFrom(args, "conversationId", null);
        ToolContext context = new ToolContext(userId, conversationId,
                TraceContext.current(), ToolContext.SOURCE_MCP);
        ToolResult result = registry.invoke(name, args, context);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("content", List.of(Map.of("type", "text", "text", toText(result.data()))));
        payload.put("structuredContent", result.data());
        payload.put("isError", !result.success());
        return success(id, payload);
    }

    private Map<String, Object> initializeResult() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", PROTOCOL_VERSION);
        result.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
        result.put("serverInfo", Map.of("name", "psy-agent", "version", "1.0.0"));
        result.put("instructions", "心理服务智能体中台的工具集。量表与预约为模拟实现，"
                + "不构成医学诊断。危机告警只传摘要，不传对话原文。");
        return result;
    }

    private boolean authorized(String tokenHeader, String authorization) {
        String provided = tokenHeader;
        if ((provided == null || provided.isBlank())
                && authorization != null && authorization.startsWith("Bearer ")) {
            provided = authorization.substring(7).trim();
        }
        // 未配置令牌时一律拒绝，避免默认放通导致统计与告警被任意调用
        return accessToken != null && !accessToken.isBlank()
                && accessToken.equals(provided);
    }

    private static Long longFrom(Map<String, Object> args, String key, Long defaultValue) {
        Object value = args.get(key);
        if (value instanceof Number number) {
            return number.longValue();
        }
        return defaultValue;
    }

    private String toText(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static Map<String, Object> success(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    private static Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("error", Map.of("code", code, "message", message));
        return response;
    }
}
