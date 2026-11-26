package com.psyagent.infra.ai;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Configuration;

/**
 * 模型装配。
 * <p>
 * 两个互斥的 Bean：有 Key 走真实模型，无 Key 走 Mock。
 * 互斥条件比"if-else 里返回 null"可靠得多——Spring 不允许 @Bean 方法返回 null。
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {

    @Bean
    @Conditional(ApiKeyPresentCondition.class)
    public AiChatFacade springAiChatFacade(ChatModel chatModel, AiProperties properties) {
        ChatClient chatClient = ChatClient.builder(chatModel)
                .defaultSystem(properties.systemPrompt())
                .build();
        return new SpringAiChatFacade(chatClient, properties);
    }

    @Bean
    @Conditional(ApiKeyAbsentCondition.class)
    public AiChatFacade mockAiChatFacade() {
        return new MockAiChatFacade();
    }
}
