package com.psyagent.bootstrap.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.psyagent.common.api.ResultCode;
import com.psyagent.common.exception.BizException;
import com.psyagent.store.entity.Conversation;
import com.psyagent.store.entity.Message;
import com.psyagent.store.mapper.ConversationMapper;
import com.psyagent.store.mapper.MessageMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话与消息。
 * <p>
 * 两个关键约束：
 * 1. **归属校验**：任何按 id 取会话的地方都必须同时比对 userId，否则改个 id 就能读别人的倾诉内容。
 * 2. **冗余字段同事务维护**：messageCount / lastMessageAt 与消息插入必须在同一事务里，
 *    否则会话列表会出现"有 3 条消息但计数为 2"的脏数据。
 */
@Service
public class ConversationService {

    private static final int TITLE_MAX_LEN = 20;

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;

    public ConversationService(ConversationMapper conversationMapper, MessageMapper messageMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
    }

    /** 取会话，不存在或不属于该用户都按"不存在"处理，避免暴露他人数据的存在性。 */
    public Conversation getOwned(Long conversationId, Long userId) {
        Conversation conversation = conversationMapper.selectById(conversationId);
        if (conversation == null || !conversation.getUserId().equals(userId)) {
            throw BizException.of(ResultCode.NOT_FOUND, "会话不存在");
        }
        return conversation;
    }

    /**
     * 新建会话。标题取首条消息截断，后续可改为模型摘要生成。
     * 选择规则而非模型生成的原因：标题生成处于首轮对话的阻塞路径，多一次模型调用即多 1–2 秒等待。
     */
    public Conversation create(Long userId, String firstMessage) {
        Conversation conversation = new Conversation();
        conversation.setUserId(userId);
        conversation.setTitle(buildTitle(firstMessage));
        conversation.setMessageCount(0);
        conversation.setStatus(1);
        conversationMapper.insert(conversation);
        return conversation;
    }

    /**
     * 追加一条消息，并同步会话的冗余字段。
     *
     * @param costMs 仅 assistant 消息有值
     */
    @Transactional(rollbackFor = Exception.class)
    public void appendMessage(Long conversationId, Long userId, String role,
                              String content, String model, Integer costMs) {
        Message message = new Message();
        message.setConversationId(conversationId);
        message.setUserId(userId);
        message.setRole(role);
        message.setContent(content);
        message.setModel(model);
        message.setCostMs(costMs);
        messageMapper.insert(message);

        conversationMapper.update(null, Wrappers.<Conversation>lambdaUpdate()
                .eq(Conversation::getId, conversationId)
                .setSql("message_count = message_count + 1")
                .set(Conversation::getLastMessageAt, LocalDateTime.now()));
    }

    public List<Conversation> listByUser(Long userId) {
        return conversationMapper.selectList(Wrappers.<Conversation>lambdaQuery()
                .eq(Conversation::getUserId, userId)
                .orderByDesc(Conversation::getLastMessageAt)
                .orderByDesc(Conversation::getId));
    }

    public List<Message> listMessages(Long conversationId, Long userId) {
        getOwned(conversationId, userId);
        return messageMapper.selectList(Wrappers.<Message>lambdaQuery()
                .eq(Message::getConversationId, conversationId)
                .orderByAsc(Message::getId));
    }

    /**
     * 回填最近一条用户消息的风险等级。
     * <p>
     * 写 user 消息而不是 assistant 消息：风险描述的是"用户此刻的状态"，
     * 危机干预检索时按 `role='user' and risk_level in ('MEDIUM','HIGH')` 定位才有意义。
     */
    public void updateLastUserRiskLevel(Long conversationId, String riskLevel) {
        if (riskLevel == null) {
            return;
        }
        Message lastUserMessage = messageMapper.selectOne(Wrappers.<Message>lambdaQuery()
                .eq(Message::getConversationId, conversationId)
                .eq(Message::getRole, "user")
                .orderByDesc(Message::getId)
                .last("limit 1"));
        if (lastUserMessage == null) {
            return;
        }
        Message patch = new Message();
        patch.setId(lastUserMessage.getId());
        patch.setRiskLevel(riskLevel);
        messageMapper.updateById(patch);
    }

    /**
     * 删除会话：仅对会话本身做逻辑删除，消息记录保留。
     * <p>
     * 消息不随之删除的原因：心理支持场景下"用户说过什么"属于审计数据，
     * 一旦允许级联清除，风险判定与问题追溯都会失去依据。会话从列表中消失即可满足用户诉求。
     */
    public void delete(Long conversationId, Long userId) {
        getOwned(conversationId, userId);
        conversationMapper.deleteById(conversationId);
    }

    private String buildTitle(String firstMessage) {
        String text = firstMessage == null ? "" : firstMessage.strip();
        if (text.isEmpty()) {
            return "新的对话";
        }
        return text.length() <= TITLE_MAX_LEN ? text : text.substring(0, TITLE_MAX_LEN) + "…";
    }
}
