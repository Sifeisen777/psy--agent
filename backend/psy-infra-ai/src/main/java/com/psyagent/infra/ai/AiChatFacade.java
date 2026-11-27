package com.psyagent.infra.ai;

import org.springframework.ai.chat.messages.Message;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * AI 对话门面。
 * <p>
 * 上层（Controller / 未来的 Agent 编排层）只依赖这个接口，不直接依赖 ChatClient：
 * 后续把单模型调用替换为多智能体编排时，上层代码无需改动；
 * 单元测试也可注入假实现，无需启动 Spring 上下文。
 */
public interface AiChatFacade {

    /** 当前是否接入了真实模型（false 表示走 Mock，用于健康检查与前端提示）。 */
    boolean available();

    /** 当前使用的模型标识，Mock 模式返回 "mock"。 */
    String modelName();

    /**
     * 同步对话（无参考资料）。
     */
    default String chat(String userId, String message, List<Message> history) {
        return chat(userId, message, history, List.of());
    }

    /**
     * 同步对话。
     *
     * @param userId     用户标识，用于日志与限流隔离
     * @param message    本轮用户输入
     * @param history    历史消息，按时间正序，不包含本轮输入；实现方负责追加本轮消息
     * @param references 检索到的知识片段，按相关度降序；为空表示本轮不启用检索增强
     * @return 模型回复
     */
    String chat(String userId, String message, List<Message> history, List<String> references);

    /**
     * 流式对话（无参考资料）。
     */
    default Flux<String> streamChat(String userId, String message, List<Message> history) {
        return streamChat(userId, message, history, List.of());
    }

    /**
     * 流式对话。每个元素为增量片段，由调用方拼接并推送，门面层不关心传输方式。
     *
     * @param references 语义同 {@link #chat(String, String, List, List)}
     */
    Flux<String> streamChat(String userId, String message, List<Message> history, List<String> references);

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
