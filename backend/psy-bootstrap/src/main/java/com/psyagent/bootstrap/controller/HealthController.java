package com.psyagent.bootstrap.controller;

import com.psyagent.common.api.R;
import com.psyagent.infra.ai.AiChatFacade;
import com.psyagent.infra.ai.ProbeResult;
import com.psyagent.store.mapper.ConversationMapper;
import com.psyagent.store.mapper.UserMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 健康检查。
 * <p>
 * 除了"进程活着"，还要暴露"模型是否接通"——这是 AI 应用和传统应用健康检查最大的差别：
 * 进程 200 但模型 Key 失效时，业务其实已经挂了。阶段 6 会把它接到 /actuator/health 的自定义 Indicator。
 */
@RestController
@RequestMapping("/api/system")
public class HealthController {

    private final AiChatFacade aiChatFacade;
    private final UserMapper userMapper;
    private final ConversationMapper conversationMapper;

    public HealthController(AiChatFacade aiChatFacade,
                            UserMapper userMapper,
                            ConversationMapper conversationMapper) {
        this.aiChatFacade = aiChatFacade;
        this.userMapper = userMapper;
        this.conversationMapper = conversationMapper;
    }

    /**
     * 存储连通性自检：真的执行一次 count，而不是只 ping 连接池。
     * Flyway 建表成功与否，打这个接口就知道。
     */
    @GetMapping("/db-check")
    public R<Map<String, Object>> dbCheck() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userCount", userMapper.selectCount(null));
        body.put("conversationCount", conversationMapper.selectCount(null));
        return R.ok(body);
    }

    /**
     * 存活探针：只证明"我是 psy-agent 且我活着"，不做任何 IO。
     * <p>
     * 为什么不能拿 {@code /health} 当探针用：它会真实调用一次模型（约 1.5 秒），
     * 前端/负载均衡如果按几百毫秒超时探测，会把正常服务判成不可用。
     * 探针要轻，健康检查可以重，两者职责不同。
     */
    @GetMapping("/ping")
    public R<Map<String, Object>> ping() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("app", "psy-agent");
        body.put("ts", System.currentTimeMillis());
        return R.ok(body);
    }

    @GetMapping("/health")
    public R<Map<String, Object>> health() {
        ProbeResult probe = aiChatFacade.probe();

        Map<String, Object> llm = new LinkedHashMap<>();
        llm.put("configured", aiChatFacade.available());
        llm.put("state", probe.state());
        llm.put("ok", probe.ok());
        llm.put("costMs", probe.costMs());
        llm.put("checkedAt", probe.checkedAt().toString());
        if (probe.detail() != null) {
            llm.put("detail", probe.detail());
        }

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("app", "psy-agent");
        body.put("model", aiChatFacade.modelName());
        // 保留旧字段：配了 Key 但 Key 无效时，它会是 true —— 判断可用性请看 llm.ok
        body.put("llmAvailable", aiChatFacade.available());
        body.put("llm", llm);
        body.put("javaVersion", System.getProperty("java.version"));
        body.put("serverTime", OffsetDateTime.now().toString());
        return R.ok(body);
    }
}
