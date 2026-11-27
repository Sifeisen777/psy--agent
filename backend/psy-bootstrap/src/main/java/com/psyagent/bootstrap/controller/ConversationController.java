package com.psyagent.bootstrap.controller;

import com.psyagent.bootstrap.security.CurrentUser;
import com.psyagent.bootstrap.service.ConversationService;
import com.psyagent.common.api.R;
import com.psyagent.store.entity.Conversation;
import com.psyagent.store.entity.Message;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话与消息查询。
 * <p>
 * 用户身份取自访问令牌；会话归属由服务层校验，越权访问统一返回"会话不存在"，
 * 不区分"不存在"与"无权限"，避免通过错误信息探测他人数据。
 */
@RestController
@RequestMapping("/api/conversation")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @GetMapping("/list")
    public R<List<Conversation>> list() {
        return R.ok(conversationService.listByUser(CurrentUser.id()));
    }

    @GetMapping("/{id}/messages")
    public R<List<Message>> messages(@PathVariable Long id) {
        return R.ok(conversationService.listMessages(id, CurrentUser.id()));
    }
}
