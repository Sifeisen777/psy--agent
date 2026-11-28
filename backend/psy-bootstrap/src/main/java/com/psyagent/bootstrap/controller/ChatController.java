package com.psyagent.bootstrap.controller;

import com.psyagent.bootstrap.agent.AgentContext;
import com.psyagent.bootstrap.agent.AgentOrchestrator;
import com.psyagent.bootstrap.observability.ChatTraceRecorder;
import com.psyagent.bootstrap.observability.CitationAuditor;
import com.psyagent.bootstrap.observability.TraceContext;
import com.psyagent.bootstrap.security.CurrentUser;
import com.psyagent.bootstrap.service.AuthService;
import com.psyagent.bootstrap.service.ConversationMemoryService;
import com.psyagent.bootstrap.service.ConversationService;
import com.psyagent.bootstrap.service.ReviewTaskService;
import com.psyagent.common.api.R;
import com.psyagent.common.api.ResultCode;
import com.psyagent.common.exception.BizException;
import com.psyagent.infra.ai.AiChatFacade;
import com.psyagent.infra.ai.EmotionAssessment;
import com.psyagent.store.entity.Conversation;
import com.psyagent.store.ratelimit.RedisRateLimiter;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
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
import reactor.core.scheduler.Schedulers;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 对话入口。
 * <p>
 * 两条通道：
 * <ul>
 *   <li>{@code POST /api/chat} —— 同步一次性返回。保留做联调与降级（模型不支持流式时），**不落库**。</li>
 *   <li>{@code POST /api/chat/stream} —— 正式通道，SSE 逐字输出并落库。</li>
 * </ul>
 * 观测数据（t_chat_trace）在所有终止路径上写一次，包括客户端断开与超时。
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
    private final ConversationMemoryService memoryService;
    private final AgentOrchestrator orchestrator;
    private final ChatTraceRecorder traceRecorder;
    private final ReviewTaskService reviewTaskService;
    private final AuthService authService;
    private final int chatLimit;
    private final boolean emotionEnabled;

    public ChatController(AiChatFacade aiChatFacade,
                          RedisRateLimiter rateLimiter,
                          ConversationService conversationService,
                          ConversationMemoryService memoryService,
                          AgentOrchestrator orchestrator,
                          ChatTraceRecorder traceRecorder,
                          ReviewTaskService reviewTaskService,
                          AuthService authService,
                          @Value("${psy.rate-limit.chat-per-minute:10}") int chatLimit,
                          @Value("${psy.ai.emotion-enabled:true}") boolean emotionEnabled) {
        this.aiChatFacade = aiChatFacade;
        this.rateLimiter = rateLimiter;
        this.conversationService = conversationService;
        this.memoryService = memoryService;
        this.orchestrator = orchestrator;
        this.traceRecorder = traceRecorder;
        this.reviewTaskService = reviewTaskService;
        this.authService = authService;
        this.chatLimit = chatLimit;
        this.emotionEnabled = emotionEnabled;
    }

    /** 同步通道：只回内容，不落库。 */
    @PostMapping
    public R<ChatReply> chat(@RequestBody @Valid ChatRequest request) {
        long startedAt = System.currentTimeMillis();
        Long userId = CurrentUser.id();
        checkRate(userId);
        authService.getById(userId);

        AgentContext context = new AgentContext(userId, null, request.message(),
                List.of(), TraceContext.current());
        orchestrator.run(context);
        if (context.isHitlRequired()) {
            reviewTaskService.createForCrisis(context);
        }

        String reply;
        try {
            reply = aiChatFacade.chat(String.valueOf(userId), request.message(),
                    List.of(), context.getSystemPrompt());
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("chat failed userId={}", userId, e);
            traceRecorder.record(context, aiChatFacade.modelName(), !aiChatFacade.available(),
                    (int) (System.currentTimeMillis() - startedAt), null, null, 0, false);
            throw BizException.of(ResultCode.AI_UNAVAILABLE);
        }
        int modelCost = (int) (System.currentTimeMillis() - startedAt);
        EmotionAssessment emotion = emotionEnabled
                ? aiChatFacade.assess(String.valueOf(userId), request.message())
                : null;
        // 规则漏判的补充：路由没命中但模型判为高风险，同样要进复核队列
        if (emotion != null && EmotionAssessment.RISK_HIGH.equals(emotion.riskLevel())) {
            reviewTaskService.createForModelHigh(context);
        }
        traceRecorder.record(context, aiChatFacade.modelName(), !aiChatFacade.available(),
                modelCost, null, emotion == null ? null : emotion.riskLevel(),
                reply.length(), false);
        int citationIssues = CitationAuditor.outOfRange(reply, context.citations().size());
        return R.ok(new ChatReply(reply, aiChatFacade.modelName(), !aiChatFacade.available(), modelCost,
                emotion, context.citations(), context.getRoute().name(),
                context.getTrace(), context.isHitlRequired(),
                context.getTraceId(), context.toolViews(), citationIssues));
    }

    /**
     * 流式通道。
     * <p>
     * 事件约定：
     * <pre>
     * event: meta   data: {"conversationId":123,"title":"...","traceId":"..."}   首帧
     * event: delta  data: 文本片段                                                多次
     * event: done   data: {"costMs":2010,"length":180,"emotion":{...},"citations":[...],
     *                      "route":"KNOWLEDGE","steps":[...],"hitl":false,
     *                      "traceId":"...","tools":[...],"ttftMs":980}          结束
     * event: error  data: 错误提示                                                异常时
     * </pre>
     * 为什么用 SseEmitter 而不是直接返回 Flux：当前是 Servlet 栈（Tomcat），
     * 直接返回 Flux 需要引入 WebFlux 走响应式适配；SseEmitter 更贴近"Servlet 异步"的真相，
     * 也便于精确控制超时与资源释放。若后续迁移至 WebFlux，可统一替换为返回 Flux。
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestBody @Valid StreamRequest request) {
        Long userId = CurrentUser.id();
        checkRate(userId);
        // 令牌未过期但账号已注销时，仍需拒绝
        authService.getById(userId);

        Conversation conversation = request.conversationId() == null
                ? conversationService.create(userId, request.message())
                : conversationService.getOwned(request.conversationId(), userId);

        // 历史必须在写入本轮消息之前加载，否则当前输入会重复出现在上下文中
        List<org.springframework.ai.chat.messages.Message> history =
                memoryService.loadHistory(conversation.getId());

        // 编排：分诊 → 知识检索 / 危机干预 → 工具，产出本轮的系统提示
        AgentContext context = new AgentContext(userId, conversation.getId(),
                request.message(), history, TraceContext.current());
        orchestrator.run(context);
        // 建单放在生成之前：危机场景的跟进不该等模型写完才开始
        if (context.isHitlRequired()) {
            reviewTaskService.createForCrisis(context);
        }

        // 用户消息在调用模型前落库，确保模型异常时仍保留用户输入记录
        conversationService.appendMessage(conversation.getId(), userId,
                "user", request.message(), null, null);

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        StringBuilder buffer = new StringBuilder();
        AtomicBoolean clientGone = new AtomicBoolean(false);
        AtomicBoolean saved = new AtomicBoolean(false);
        AtomicBoolean traced = new AtomicBoolean(false);
        AtomicInteger ttftMs = new AtomicInteger(-1);
        AtomicReference<String> riskLevel = new AtomicReference<>();
        long start = System.currentTimeMillis();

        // 兜底保存：客户端断开 / 超时 / 异常 / 正常结束均会触发，CAS 保证只存一次。
        // dispose 订阅后 onComplete 不再执行，仅依赖完成回调会在客户端断连时丢失已生成内容。
        Runnable saveOnce = () -> {
            if (buffer.isEmpty() || !saved.compareAndSet(false, true)) {
                return;
            }
            try {
                conversationService.appendMessage(conversation.getId(), userId,
                        "assistant", buffer.toString(), aiChatFacade.modelName(),
                        (int) (System.currentTimeMillis() - start));
            } catch (Exception e) {
                log.error("sse save failed conversationId={}", conversation.getId(), e);
            }
        };

        // 观测轨迹同样覆盖所有终止路径。漏掉失败路径会让看板的成功率虚高，
        // 而失败恰恰是最需要被看见的那部分。
        Runnable traceOnce = () -> {
            if (!traced.compareAndSet(false, true)) {
                return;
            }
            int firstToken = ttftMs.get();
            traceRecorder.record(context, aiChatFacade.modelName(), !aiChatFacade.available(),
                    (int) (System.currentTimeMillis() - start),
                    firstToken < 0 ? null : firstToken,
                    riskLevel.get(), buffer.length(), clientGone.get());
        };

        try {
            Map<String, Object> meta = new LinkedHashMap<>();
            meta.put("conversationId", conversation.getId());
            meta.put("title", conversation.getTitle());
            meta.put("traceId", context.getTraceId());
            emitter.send(SseEmitter.event().name("meta").data(meta));
        } catch (IOException e) {
            log.warn("sse meta send failed, client gone before start");
            saveOnce.run();
            traceOnce.run();
            emitter.complete();
            return emitter;
        }

        Disposable subscription = aiChatFacade
                .streamChat(String.valueOf(userId), request.message(), history, context.getSystemPrompt())
                .subscribe(
                        chunk -> {
                            buffer.append(chunk);
                            ttftMs.compareAndSet(-1, (int) (System.currentTimeMillis() - start));
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
                            traceOnce.run();
                            if (!clientGone.get()) {
                                try {
                                    emitter.send(SseEmitter.event().name("error").data("生成失败，请稍后重试"));
                                } catch (IOException ignored) {
                                    // 客户端已断开
                                }
                            }
                            emitter.complete();
                        },
                        () -> Schedulers.boundedElastic().schedule(() -> {
                            saveOnce.run();
                            // 评估是一次独立的模型调用，放在弹性线程池执行：
                            // 回调本身跑在响应式线程上，直接阻塞会拖慢同一线程上的其他请求
                            EmotionAssessment emotion = emotionEnabled
                                    ? aiChatFacade.assess(String.valueOf(userId), request.message())
                                    : null;
                            if (emotion != null) {
                                riskLevel.set(emotion.riskLevel());
                                if (EmotionAssessment.RISK_HIGH.equals(emotion.riskLevel())) {
                                    reviewTaskService.createForModelHigh(context);
                                }
                                try {
                                    conversationService.updateLastUserRiskLevel(
                                            conversation.getId(), emotion.riskLevel());
                                } catch (Exception e) {
                                    // 回填失败不影响主流程，风险等级可由日志与重放补齐
                                    log.error("risk level update failed conversationId={}",
                                            conversation.getId(), e);
                                }
                            }
                            if (!clientGone.get()) {
                                try {
                                    Map<String, Object> done = new LinkedHashMap<>();
                                    done.put("costMs", (int) (System.currentTimeMillis() - start));
                                    done.put("length", buffer.length());
                                    done.put("emotion", emotion);
                                    done.put("citations", context.citations());
                                    done.put("route", context.getRoute().name());
                                    done.put("steps", context.getTrace());
                                    done.put("hitl", context.isHitlRequired());
                                    done.put("traceId", context.getTraceId());
                                    done.put("tools", context.toolViews());
                                    // 近 24 小时高风险条数：前端可据此决定是否保留关注提示
                                    done.put("priorHighRisk", context.getPriorHighRiskCount());
                                    int firstToken = ttftMs.get();
                                    done.put("ttftMs", firstToken < 0 ? null : firstToken);
                                    // 引用越界只记录不阻断：流式内容已发出，撤回不存在
                                    done.put("citationIssues", CitationAuditor.outOfRange(
                                            buffer.toString(), context.citations().size()));
                                    emitter.send(SseEmitter.event().name("done").data(done));
                                } catch (IOException ignored) {
                                    // 客户端已断开，无需再推
                                }
                            }
                            traceOnce.run();
                            emitter.complete();
                        }));

        emitter.onCompletion(() -> {
            saveOnce.run();
            traceOnce.run();
            subscription.dispose();
        });
        emitter.onTimeout(() -> {
            log.warn("sse timeout conversationId={}", conversation.getId());
            saveOnce.run();
            traceOnce.run();
            subscription.dispose();
        });
        emitter.onError(e -> {
            saveOnce.run();
            traceOnce.run();
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

    /** 同步请求体。用户身份来自访问令牌，请求体不再携带 userId。 */
    public record ChatRequest(
            @NotBlank(message = "message 不能为空")
            @Size(max = 2000, message = "单条消息不能超过 2000 字") String message
    ) {
    }

    /** 流式请求体：conversationId 为空表示新建会话。 */
    public record StreamRequest(
            Long conversationId,
            @NotBlank(message = "message 不能为空")
            @Size(max = 2000, message = "单条消息不能超过 2000 字") String message
    ) {
    }

    public record ChatReply(String reply, String model, boolean mock, int costMs,
                            EmotionAssessment emotion, List<Map<String, Object>> citations,
                            String route, List<AgentContext.AgentStep> steps, boolean hitl,
                            String traceId, List<Map<String, Object>> tools, int citationIssues) {
    }
}
