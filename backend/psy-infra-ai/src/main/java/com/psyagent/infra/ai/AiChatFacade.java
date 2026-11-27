package com.psyagent.infra.ai;

import org.springframework.ai.chat.messages.Message;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * AI 对话门面。
 * <p>
 * 职责边界：只负责"把一次对话交给模型并取回结果"，不决定对话策略。
 * 人设、检索资料、风险约束等系统提示由上层编排层拼装后传入，
 * 这样同一模型可以被不同策略复用，门面不必随业务规则变化而修改。
 * <p>
 * 上层（Controller / Agent 编排层）只依赖这个接口，不直接依赖 ChatClient：
 * 替换模型提供方或引入多智能体编排时，业务代码无需改动；
 * 单元测试也可注入假实现，无需启动 Spring 上下文。
 */
public interface AiChatFacade {

    /** 当前是否接入了真实模型（false 表示走 Mock，用于健康检查与前端提示）。 */
    boolean available();

    /** 当前使用的模型标识，Mock 模式返回 "mock"。 */
    String modelName();

    /** 同步对话，使用配置中的默认系统提示。 */
    default String chat(String userId, String message, List<Message> history) {
        return chat(userId, message, history, null);
    }

    /**
     * 同步对话。
     *
     * @param userId       用户标识，用于日志与限流隔离
     * @param message      本轮用户输入
     * @param history      历史消息，按时间正序，不包含本轮输入；实现方负责追加本轮消息
     * @param systemPrompt 完整系统提示（人设 + 策略 + 参考资料）；为 null 时使用配置中的默认人设
     * @return 模型回复
     */
    String chat(String userId, String message, List<Message> history, String systemPrompt);

    /** 流式对话，使用配置中的默认系统提示。 */
    default Flux<String> streamChat(String userId, String message, List<Message> history) {
        return streamChat(userId, message, history, null);
    }

    /**
     * 流式对话。每个元素为增量片段，由调用方拼接并推送，门面层不关心传输方式。
     *
     * @param systemPrompt 语义同 {@link #chat(String, String, List, String)}
     */
    Flux<String> streamChat(String userId, String message, List<Message> history, String systemPrompt);

    /**
     * 真实探活：使用当前密钥发起一次最小请求，确认模型侧可用。
     * 实现方需自带缓存（默认 60 秒），避免健康检查产生额外费用与延迟。
     */
    ProbeResult probe();

    /**
     * 情绪与风险评估，返回可直接入库的结构化结果。
     * <p>
     * 契约要点：**本方法不抛异常**。评估属于旁路能力，失败时应降级为规则判定并继续，
     * 不能让"判断情绪"这件事把已经生成好的回复链路拖垮。
     *
     * @param userId      用户标识，仅用于日志
     * @param userMessage 本轮用户输入
     * @return 归一化后的评估结果，永不为 null
     */
    EmotionAssessment assess(String userId, String userMessage);
}
