package com.psyagent.bootstrap.controller;

import com.psyagent.common.api.R;
import com.psyagent.common.api.ResultCode;
import com.psyagent.common.exception.BizException;
import com.psyagent.infra.ai.AiChatFacade;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对话入口（阶段 1 版本：单模型直答）。
 * <p>
 * 阶段 2 会把这个类的内部实现换成 SupervisorAgent 编排，对外契约保持不变——
 * 这就是先用 AiChatFacade 隔离的好处：改造时前端不用改一行。
 */
@RestController
@RequestMapping("/api/chat")
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final AiChatFacade aiChatFacade;

    public ChatController(AiChatFacade aiChatFacade) {
        this.aiChatFacade = aiChatFacade;
    }

    @PostMapping
    public R<ChatReply> chat(@RequestBody @Valid ChatRequest request) {
        long start = System.currentTimeMillis();
        String reply;
        try {
            reply = aiChatFacade.chat(request.userId(), request.message());
        } catch (BizException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("chat failed userId={}", request.userId(), e);
            throw BizException.of(ResultCode.AI_UNAVAILABLE);
        }
        long cost = System.currentTimeMillis() - start;
        return R.ok(new ChatReply(reply, aiChatFacade.modelName(), !aiChatFacade.available(), cost));
    }

    /** 请求体。record + Jakarta Validation，字段不可变，天然线程安全。 */
    public record ChatRequest(
            @NotBlank(message = "userId 不能为空")
            @Size(max = 64, message = "userId 过长")
            String userId,

            @NotBlank(message = "message 不能为空")
            @Size(max = 2000, message = "单条消息不能超过 2000 字")
            String message
    ) {
    }

    /**
     * 响应体。
     *
     * @param reply 模型回复
     * @param model 实际使用的模型
     * @param mock  是否为 Mock 模式（前端据此显示"未接入模型"提示条）
     * @param costMs 本次耗时，便于前端展示与后续埋点
     */
    public record ChatReply(String reply, String model, boolean mock, long costMs) {
    }
}
