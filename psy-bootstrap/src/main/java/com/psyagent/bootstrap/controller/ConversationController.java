package com.psyagent.bootstrap.controller;

import com.psyagent.bootstrap.service.ConversationService;
import com.psyagent.common.api.R;
import com.psyagent.store.entity.Conversation;
import com.psyagent.store.entity.Message;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 会话与消息查询。
 * <p>
 * 所有接口都强制带 userId：服务层会校验会话归属，越权访问统一返回"会话不存在"，
 * 不区分"不存在"与"无权限"——避免通过错误信息探测他人数据的存在性。
 */
@RestController
@RequestMapping("/api/conversation")
public class ConversationController {

    private final ConversationService conversationService;

    public ConversationController(ConversationService conversationService) {
        this.conversationService = conversationService;
    }

    @GetMapping("/list")
    public R<List<Conversation>> list(@RequestParam Long userId) {
        return R.ok(conversationService.listByUser(userId));
    }

    @GetMapping("/{id}/messages")
    public R<List<Message>> messages(@PathVariable Long id, @RequestParam Long userId) {
        return R.ok(conversationService.listMessages(id, userId));
    }
}
