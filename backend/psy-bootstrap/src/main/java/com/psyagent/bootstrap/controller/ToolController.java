package com.psyagent.bootstrap.controller;

import com.psyagent.bootstrap.observability.TraceContext;
import com.psyagent.bootstrap.security.CurrentUser;
import com.psyagent.bootstrap.tool.ToolContext;
import com.psyagent.bootstrap.tool.ToolRegistry;
import com.psyagent.bootstrap.tool.ToolResult;
import com.psyagent.common.api.R;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 工具直调入口。
 * <p>
 * 三个用途：前端工具面板、评测脚本、以及"不经过对话也想用工具"的场景。
 * 与 MCP 端点共用同一个注册表，因此行为一致、审计也落在同一张表。
 * <p>
 * 这里的身份来自 JWT（{@link CurrentUser}），MCP 端点则用独立令牌——
 * 两套入口的身份来源不同，但工具本身不关心，只消费 {@link ToolContext}。
 */
@RestController
@RequestMapping("/api/tools")
public class ToolController {

    private final ToolRegistry registry;

    public ToolController(ToolRegistry registry) {
        this.registry = registry;
    }

    /** 可用工具清单（含 JSON Schema），前端据此渲染表单。 */
    @GetMapping
    public R<List<Map<String, Object>>> list() {
        return R.ok(registry.descriptors());
    }

    @PostMapping("/{name}")
    public R<ToolResult> invoke(@PathVariable String name,
                                @RequestBody(required = false) Map<String, Object> args) {
        Map<String, Object> arguments = args == null ? Map.of() : args;
        Long conversationId = null;
        Object raw = arguments.get("conversationId");
        if (raw instanceof Number number) {
            conversationId = number.longValue();
        }
        ToolContext context = new ToolContext(CurrentUser.id(), conversationId,
                TraceContext.current(), ToolContext.SOURCE_REST);
        return R.ok(registry.invoke(name, arguments, context));
    }
}
