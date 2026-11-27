package com.psyagent.bootstrap.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.psyagent.infra.ai.AiProperties;
import com.psyagent.store.entity.Message;
import com.psyagent.store.mapper.MessageMapper;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 会话记忆。
 * <p>
 * 从 {@code t_message} 读取最近的若干条消息，转换为模型可接受的消息序列，注入到本轮请求。
 * 记忆以数据库为准而非进程内缓存：服务重启、多实例部署时上下文仍然连续。
 */
@Service
public class ConversationMemoryService {

    private final MessageMapper messageMapper;
    private final int maxMessages;

    public ConversationMemoryService(MessageMapper messageMapper, AiProperties aiProperties) {
        this.messageMapper = messageMapper;
        this.maxMessages = aiProperties.memoryMaxMessages();
    }

    /**
     * 加载指定会话的历史消息，按时间正序返回，不包含尚未写入的本轮输入。
     * <p>
     * 采用"倒序取 N 条再反转"：直接正序取会取到最早的消息，
     * 对话变长后模型看到的永远是开头几轮。
     */
    public List<org.springframework.ai.chat.messages.Message> loadHistory(Long conversationId) {
        if (conversationId == null) {
            return List.of();
        }
        List<Message> rows = messageMapper.selectList(Wrappers.<Message>lambdaQuery()
                .eq(Message::getConversationId, conversationId)
                .orderByDesc(Message::getId)
                .last("LIMIT " + maxMessages));

        if (rows.isEmpty()) {
            return List.of();
        }

        List<Message> ordered = new ArrayList<>(rows);
        Collections.reverse(ordered);

        List<org.springframework.ai.chat.messages.Message> result = new ArrayList<>(ordered.size());
        for (Message row : ordered) {
            org.springframework.ai.chat.messages.Message converted = convert(row);
            if (converted != null) {
                result.add(converted);
            }
        }
        return result;
    }

    public int maxMessages() {
        return maxMessages;
    }

    private static org.springframework.ai.chat.messages.Message convert(Message row) {
        if (row.getContent() == null || row.getContent().isBlank()) {
            return null;
        }
        if ("assistant".equalsIgnoreCase(row.getRole())) {
            return new AssistantMessage(row.getContent());
        }
        if ("user".equalsIgnoreCase(row.getRole())) {
            return new UserMessage(row.getContent());
        }
        // system 消息由全局人设统一提供，不参与历史拼接
        return null;
    }
}
