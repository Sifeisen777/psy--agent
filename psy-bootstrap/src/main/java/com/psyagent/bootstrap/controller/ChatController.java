package com.psyagent.bootstrap.controller;

import com.psyagent.bootstrap.service.AuthService;
import com.psyagent.bootstrap.service.ConversationService;
import com.psyagent.common.api.R;
import com.psyagent.common.api.ResultCode;
import com.psyagent.common.exception.BizException;
import com.psyagent.infra.ai.AiChatFacade;
import com.psyagent.store.entity.Conversation;
import com.psyagent.store.ratelimit.RedisRateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.Disposable;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 对话入口。
 * <p>
 * 两条通道：
 * <ul>
 *   <li>{@code POST /api/chat} —— 同步一次性返回。保留做联调与降级（模型不支持流式时），**不落库**。</li>
 *   <li>{@code POST /api/chat/stream} —— 正式通道，SSE 逐字输出并落库。</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    /** 单次流式会话最长 120 秒，超时触发 onTimeout，前端收到连接关闭。 */
    private static final long SSE_TIMEOUT_MS = 120_000L;

    private final AiChatFacade aiChatFacade;
    private final RedisRateLimiter rateLimiter;
    private final ConversationService conversationService;
    private final AuthService authService;
    private final int chatLimit;

    public ChatController(AiChatFacade aiChatFacade,
                          RedisRateLimiter rateLimiter,
                          ConversationService conversationService,
                          AuthService authService,
                          @Value("${psy.rate-limit.chat-per-minute:10}") int chatLimit) {
        this.aiChatFacade = aiChatFacade;
        this.rateLimiter = rateLimiter;
        this.conversationService = conversationService;
        this.authService = authService;
        this.chatLimit = chatLimit;
    }

    /** 同步通道：只回内容，不落库。 */
    @PostMapping
    public R<ChatReply> chat(@RequestBody @Valid ChatRequest request) {
        checkRate(request.userId());
        authService.getById(request.userId());

        long start = System.currentTimeMillis();
        String reply;
        try {
            reply = aiChatFacade.chat(String.valueOf(request.userId()), request.message());
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("chat failed userId={}", request.userId(), e);
            throw BizException.of(ResultCode.AI_UNAVAILABLE);
        }
        long cost = System.currentTimeMillis() - start;
        return R.ok(new ChatReply(reply, aiChatFacade.modelName(), !aiChatFacade.available(), cost));
    }

    /**
     * 流式通道。
     * <p>
     * 事件约定：
     * <pre>
     * event: meta   data: {"conversationId":123,"title":"..."}   首帧，前端拿到会话 id
     * event: delta  data: 文本片段                                多次
     * event: done   data: {"costMs":2010,"length":180}            结束
     * event: error  data: 错误提示                                异常时
     * </pre>
     * 为什么用 SseEmitter 而不是直接返回 Flux：当前是 Servlet 栈（Tomcat），
     * 直接返回 Flux 需要引入 WebFlux 走响应式适配；SseEmitter 更贴近"Servlet 异步"的真相，
     * 也便于精确控制超时与资源释放。阶段 8 若整体迁移 WebFlux 再统一替换。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody @Valid StreamRequest request) {
        checkRate(request.userId());
        // 用户必须真实存在：否则任意 userId 都能创建会话，产生孤儿数据且绕过归属校验
        authService.getById(request.userId());

        Conversation conversation = request.conversationId() == null
                ? conversationService.create(request.userId(), request.message())
                : conversationService.getOwned(request.conversationId(), request.userId());

        // 用户消息先落库：即使模型失败也要留痕（心理场景下"用户说了什么"比"AI 回了什么"更重要）
        conversationService.appendMessage(conversation.getId(), request.userId(),
                "user", request.message(), null, null);

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        StringBuilder buffer = new StringBuilder();
        AtomicBoolean clientGone = new AtomicBoolean(false);
        AtomicBoolean saved = new AtomicBoolean(false);
        long start = System.currentTimeMillis();

        // 兜底保存：客户端断开 / 超时 / 异常 / 正常结束都可能触发，CAS 保证只存一次。
        // 为什么必须兜底：dispose 订阅后 onComplete 不再执行。实测发现用户一刷新页面，
        // 已生成的内容就全丢了——只靠 onComplete 落库是不可靠的。
        Runnable saveOnce = () -> {
            if (buffer.isEmpty() || !saved.compareAndSet(false, true)) {
                return;
            }
            try {
                conversationService.appendMessage(conversation.getId(), request.userId(),
                        "assistant", buffer.toString(), aiChatFacade.modelName(),
                        (int) (System.currentTimeMillis() - start));
            } catch (Exception e) {
                log.error("sse save failed conversationId={}", conversation.getId(), e);
            }
        };

        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("conversationId", conversation.getId());
            meta.put("title", conversation.getTitle());
            emitter.send(SseEmitter.event().name("meta").data(meta));
        } catch (IOException e) {
            log.warn("sse meta send failed, client gone before start");
            emitter.complete();
            return emitter;
        }

        Disposable subscription = aiChatFacade
                .streamChat(String.valueOf(request.userId()), request.message())
                .subscribe(
                        chunk -> {
                            buffer.append(chunk);
                            if (clientGone.get()) {
                                return;
                            }
                            try {
                                emitter.send(SseEmitter.event().name("delta").data(chunk));
                            } catch (IOException e) {
                                // 客户端断开：停止推送，但让生成走完，保证落库内容完整
                                clientGone.set(true);
                                log.info("sse client disconnected conversationId={}", conversation.getId());
                            }
                        },
                        error -> {
                            log.warn("sse stream error conversationId={}", conversation.getId(), error);
                            saveOnce.run();
                            if (!clientGone.get()) {
                                try {
                                    emitter.send(SseEmitter.event().name("error").data("生成失败，请稍后重试"));
                                } catch (IOException ignored) {
                                    // 客户端已断开
                                }
                            }
                            emitter.complete();
                        },
                        () -> {
                            saveOnce.run();
                            if (!clientGone.get()) {
                                try {
                                    Map<String, Object> done = new LinkedHashMap<>();
                                    done.put("costMs", System.currentTimeMillis() - start);
                                    done.put("length", buffer.length());
                                    emitter.send(SseEmitter.event().name("done").data(done));
                                } catch (IOException ignored) {
                                    // 客户端已断开，无需再推
                                }
                            }
                            emitter.complete();
                        });

        emitter.onCompletion(() -> {
            saveOnce.run();
            subscription.dispose();
        });
        emitter.onTimeout(() -> {
            log.warn("sse timeout conversationId={}", conversation.getId());
            saveOnce.run();
            subscription.dispose();
        });
        emitter.onError(e -> {
            saveOnce.run();
            subscription.dispose();
        });
        return emitter;
    }

    private void checkRate(Long userId) {
        if (!rateLimiter.tryAcquire("rl:chat:" + userId, chatLimit, 60)) {
            log.warn("rate limited userId={} limit={}/min", userId, chatLimit);
            throw BizException.of(ResultCode.RATE_LIMITED);
        }
    }

    /**
     * 同步请求体。
     * <p>
     * userId 从 String 收紧为 Long：它直接参与会话归属校验，
     * 允许任意字符串等于把越权访问的入口敞开。
     */
    public record ChatRequest(
            @NotNull(message = "userId 不能为空") Long userId,
            @NotBlank(message = "message 不能为空")
            @Size(max = 2000, message = "单条消息不能超过 2000 字") String message
    ) {
    }

    /** 流式请求体：conversationId 为空表示新建会话。 */
    public record StreamRequest(
            @NotNull(message = "userId 不能为空") Long userId,
            Long conversationId,
            @NotBlank(message = "message 不能为空")
            @Size(max = 2000, message = "单条消息不能超过 2000 字") String message
    ) {
    }

    public record ChatReply(String reply, String model, boolean mock, long costMs) {
    }
}
