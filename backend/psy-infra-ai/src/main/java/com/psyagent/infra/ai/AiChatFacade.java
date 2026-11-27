package com.psyagent.infra.ai;

import org.springframework.ai.chat.messages.Message;

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
     * 同步对话。
     *
     * @param userId  用户标识，用于日志与限流隔离
     * @param message 本轮用户输入
     * @param history 历史消息，按时间正序，不包含本轮输入；实现方负责追加本轮消息
     * @return 模型回复
     */
    String chat(String userId, String message, List<Message> history);

    /**
     * 流式对话。每个元素为增量片段，由调用方拼接并推送，门面层不关心传输方式。
     *
     * @param history 语义同 {@link #chat(String, String, List)}
     */
    reactor.core.publisher.Flux<String> streamChat(String userId, String message, List<Message> history);

    /**
     * 真实探活：使用当前密钥发起一次最小请求，确认模型侧可用。
     * 实现方需自带缓存（默认 60 秒），避免健康检查产生额外费用与延迟。
     */
    ProbeResult probe();
}
