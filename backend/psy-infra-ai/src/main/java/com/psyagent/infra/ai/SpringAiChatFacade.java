package com.psyagent.infra.ai;

import com.psyagent.common.api.ResultCode;
import com.psyagent.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import reactor.core.publisher.Flux;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 真实模型实现（OpenAI 兼容协议，DeepSeek / 通义 / 智谱 / vLLM 都能直接接）。
 */
public class SpringAiChatFacade implements AiChatFacade {

    private static final Logger log = LoggerFactory.getLogger(SpringAiChatFacade.class);

    /** 探活结果缓存时间：60 秒。健康检查可能被每 5 秒轮询，不缓存会把模型账单打爆。 */
    private static final long PROBE_TTL_MS = 60_000L;

    private final ChatClient chatClient;
    private final AiProperties properties;
    private final AtomicReference<ProbeResult> probeCache = new AtomicReference<>();

    public SpringAiChatFacade(ChatClient chatClient, AiProperties properties) {
        this.chatClient = chatClient;
        this.properties = properties;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String modelName() {
        return properties.model();
    }

    @Override
    public String chat(String userId, String message) {
        long start = System.currentTimeMillis();
        try {
            String content = chatClient.prompt()
                    .user(message)
                    .call()
                    .content();
            log.info("ai call ok user={} model={} cost={}ms", userId, properties.model(),
                    System.currentTimeMillis() - start);
            return content;
        } catch (RuntimeException e) {
            long cost = System.currentTimeMillis() - start;
            // 鉴权失败与"模型宕机"对运维是两件事：前者改配置，后者扩容/降级
            if (isAuthError(e)) {
                log.warn("ai auth failed user={} model={} cost={}ms", userId, properties.model(), cost);
                throw BizException.of(ResultCode.AI_AUTH_FAILED);
            }
            log.warn("ai call failed user={} model={} cost={}ms", userId, properties.model(), cost, e);
            throw e;
        }
    }

    @Override
    public Flux<String> streamChat(String userId, String message) {
        long start = System.currentTimeMillis();
        return chatClient.prompt()
                .user(message)
                .stream()
                .content()
                .doOnComplete(() -> log.info("ai stream done user={} cost={}ms", userId,
                        System.currentTimeMillis() - start))
                .doOnError(e -> {
                    // 流式是异步的，无法把异常翻译成 HTTP 状态码，只能记日志 + 让 SSE 发 error 事件
                    if (isAuthError(e)) {
                        log.warn("ai stream auth failed user={}", userId);
                    } else {
                        log.warn("ai stream failed user={}", userId, e);
                    }
                });
    }

    @Override
    public ProbeResult probe() {
        ProbeResult cached = probeCache.get();
        if (cached != null && System.currentTimeMillis() - cached.checkedAt().toEpochMilli() < PROBE_TTL_MS) {
            return cached;
        }
        long start = System.currentTimeMillis();
        ProbeResult result;
        try {
            // 只要 1 个 token：目的是验证鉴权与连通性，不是获取内容
            chatClient.prompt()
                    .user("ping")
                    .options(ChatOptions.builder().maxTokens(1).build())
                    .call()
                    .content();
            result = ProbeResult.ok(System.currentTimeMillis() - start);
        } catch (RuntimeException e) {
            long cost = System.currentTimeMillis() - start;
            result = isAuthError(e)
                    ? ProbeResult.authFailed(cost)
                    : ProbeResult.error(truncate(rootMessage(e)), cost);
            log.warn("llm probe failed state={} cost={}ms", result.state(), cost);
        }
        probeCache.set(result);
        return result;
    }

    /**
     * 401 会被 Spring AI 包成 NonTransientAiException，异常链里没有专门类型，
     * 只能靠状态码与报文判定。这是兜底识别，不是最优解——
     * 阶段 8 换成自定义 ResponseErrorHandler 后可拿到结构化错误。
     */
    private static boolean isAuthError(Throwable e) {
        for (Throwable t = e; t != null && t.getCause() != t; t = t.getCause()) {
            String msg = t.getMessage();
            if (msg == null) {
                continue;
            }
            String lower = msg.toLowerCase();
            if (msg.contains("401") || lower.contains("authentication fails") || lower.contains("unauthorized")) {
                return true;
            }
        }
        return false;
    }

    private static String rootMessage(Throwable e) {
        Throwable t = e;
        while (t.getCause() != null && t.getCause() != t) {
            t = t.getCause();
        }
        return t.getClass().getSimpleName() + ": " + t.getMessage();
    }

    private static String truncate(String s) {
        if (s == null) {
            return null;
        }
        return s.length() <= 200 ? s : s.substring(0, 200) + "...";
    }
}
