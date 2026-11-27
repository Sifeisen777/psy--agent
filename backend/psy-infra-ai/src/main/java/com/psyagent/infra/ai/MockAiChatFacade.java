package com.psyagent.infra.ai;

import org.springframework.ai.chat.messages.Message;
import reactor.core.publisher.Flux;

import java.time.Duration;
import java.util.List;

/**
 * 无模型密钥时的降级实现。
 * <p>
 * 保证未配置外部依赖时服务仍可启动并完成链路联调，CI 环境也无需外网与密钥。
 * 实现仅做回显，不模拟推理结果，避免与真实调用混淆。
 */
public class MockAiChatFacade implements AiChatFacade {

    @Override
    public boolean available() {
        return false;
    }

    @Override
    public String modelName() {
        return "mock";
    }

    @Override
    public String chat(String userId, String message, List<Message> history) {
        int historySize = history == null ? 0 : history.size();
        return "【Mock 模式】未配置模型密钥，本次未调用真实模型。\n"
                + "已携带历史消息 " + historySize + " 条，本轮输入：「" + message + "」\n"
                + "配置 psy.ai.api-key 后可获得真实回复。";
    }

    @Override
    public Flux<String> streamChat(String userId, String message, List<Message> history) {
        // 逐字输出，便于前端在无密钥环境下验证流式渲染与断线处理
        return Flux.fromArray(chat(userId, message, history).split(""))
                .delayElements(Duration.ofMillis(8));
    }

    @Override
    public ProbeResult probe() {
        return ProbeResult.mock();
    }
}
