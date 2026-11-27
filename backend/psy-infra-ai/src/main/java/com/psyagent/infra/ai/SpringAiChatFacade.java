package com.psyagent.infra.ai;

import com.psyagent.common.api.ResultCode;
import com.psyagent.common.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.ChatOptions;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 真实模型实现（OpenAI 兼容协议，DeepSeek / 通义 / 智谱 / vLLM 都能直接接）。
 */
public class SpringAiChatFacade implements AiChatFacade {

    private static final Logger log = LoggerFactory.getLogger(SpringAiChatFacade.class);

    /** 探活结果缓存时间：60 秒。健康检查可能被每 5 秒轮询，不缓存会把模型账单打爆。 */
    private static final long PROBE_TTL_MS = 60_000L;

    /**
     * 评估用提示词。与对话人设完全隔离：这里要求冷静客观，不需要共情语气。
     * 风险分级标准写死在提示词里，保证多次调用之间口径一致。
     */
    private static final String ASSESS_SYSTEM_PROMPT = """
            你是心理风险评估模块，只做判断，不做安慰。根据用户的一句话输出结构化评估。

            风险等级判定标准：
            - HIGH：出现自杀、自残、轻生、结束生命的意图或计划，或表达"活不下去"。
            - MEDIUM：强烈无望感、情绪耗竭、社会支持缺失，但无明确自伤意图。
            - LOW：有情绪困扰，但表达中有应对意愿或现实联结。
            - NONE：情绪平稳或积极。

            emotion 用两个汉字概括主要情绪（如 焦虑、低落、愤怒、平静）。
            intensity 为 0–100 的整数，表示情绪强烈程度。
            reason 不超过 40 字，引用用户原话中的关键表述作为依据。
            suggestion 为一句可执行的具体建议，不超过 50 字。

            只输出 JSON 对象本身，不要输出任何解释、前后缀或代码块标记。
            """;

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
    public String chat(String userId, String message, List<Message> history) {
        long start = System.currentTimeMillis();
        try {
            String content = chatClient.prompt()
                    .messages(withCurrentMessage(history, message))
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
    public Flux<String> streamChat(String userId, String message, List<Message> history) {
        long start = System.currentTimeMillis();
        return chatClient.prompt()
                .messages(withCurrentMessage(history, message))
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

    /**
     * 情绪评估：独立一次调用，温度 0、限制输出长度。
     * <p>
     * 不复用主对话的回复：让模型在"安慰用户"的同时输出 JSON，实测会污染回复语气，
     * 且流式场景下无法在生成中途插入结构化数据。旁路一次短调用更稳，代价约 300–800ms。
     */
    @Override
    public EmotionAssessment assess(String userId, String userMessage) {
        long start = System.currentTimeMillis();
        try {
            EmotionAssessment raw = chatClient.prompt()
                    .system(ASSESS_SYSTEM_PROMPT)
                    .user(userMessage)
                    .options(ChatOptions.builder().temperature(0.0).maxTokens(300).build())
                    .call()
                    .entity(EmotionAssessment.class);
            if (raw == null) {
                throw new IllegalStateException("模型返回了空评估结果");
            }
            EmotionAssessment result = raw.normalized().withSource("MODEL");
            log.info("emotion assessed user={} emotion={} risk={} cost={}ms", userId,
                    result.emotion(), result.riskLevel(), System.currentTimeMillis() - start);
            return result;
        } catch (RuntimeException e) {
            // 评估失败不向上抛：已生成的回复不受影响，改用规则判定保证系统仍能响应风险
            log.warn("emotion assess failed, fallback to rule user={} cost={}ms", userId,
                    System.currentTimeMillis() - start, e);
            return RuleBasedEmotionJudge.judge(userMessage);
        }
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
            result = ProbeResult.ok((int) (System.currentTimeMillis() - start));
        } catch (RuntimeException e) {
            int cost = (int) (System.currentTimeMillis() - start);
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
     * 可通过自定义 ResponseErrorHandler 获取结构化错误。
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

    /** 历史消息在前，本轮输入在最后，顺序即模型看到的对话顺序。 */
    private static List<Message> withCurrentMessage(List<Message> history, String message) {
        List<Message> messages = new ArrayList<>(history == null ? List.of() : history);
        messages.add(new UserMessage(message));
        return messages;
    }
}
