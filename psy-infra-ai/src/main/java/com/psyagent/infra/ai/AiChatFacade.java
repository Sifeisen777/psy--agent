package com.psyagent.infra.ai;

/**
 * AI 对话门面。
 * <p>
 * 上层（Controller / 未来 Agent 编排层）只依赖这个接口，不直接碰 ChatClient。
 * 好处：阶段 2 把"单模型调用"换成"多 Agent 编排"时，上层代码零改动；
 * 单元测试可以直接注入假实现，不用起 Spring 上下文。
 */
public interface AiChatFacade {

    /** 当前是否接入了真实模型（false 说明走 Mock，用于健康检查与前端提示）。 */
    boolean available();

    /** 当前使用的模型标识，Mock 模式返回 "mock"。 */
    String modelName();

    /**
     * 单轮对话。
     *
     * @param userId  用户标识，用于日志与后续记忆隔离（阶段 5 才会真正用到）
     * @param message 用户输入
     * @return 模型回复
     */
    String chat(String userId, String message);

    /**
     * 真实探活：拿当前 Key 实际发一次最小请求，确认模型侧可用。
     * 实现方必须自带缓存（默认 60 秒），避免每次健康检查都产生费用与延迟。
     */
    ProbeResult probe();
}
