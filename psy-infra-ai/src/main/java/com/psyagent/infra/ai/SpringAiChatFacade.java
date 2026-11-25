package com.psyagent.infra.ai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;

/**
 * 真实模型实现（OpenAI 兼容协议，DeepSeek / 通义 / 智谱 / vLLM 都能直接接）。
 */
public class SpringAiChatFacade implements AiChatFacade {

    private static final Logger log = LoggerFactory.getLogger(SpringAiChatFacade.class);

    private final ChatClient chatClient;
    private final AiProperties properties;

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
            // 这里先不吞异常，交给上层统一处理；阶段 6 会加熔断与降级
            log.warn("ai call failed user={} model={} cost={}ms", userId, properties.model(),
                    System.currentTimeMillis() - start, e);
            throw e;
        }
    }
}
