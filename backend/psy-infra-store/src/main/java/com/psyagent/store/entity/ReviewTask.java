package com.psyagent.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 人工复核工单。
 * <p>
 * 与 t_chat_trace 的分工：轨迹回答"这一轮系统做了什么"，工单回答"这一轮有没有人跟进"。
 * 两者用 trace_id 关联，一单一链路（唯一键约束），重复触发不会制造重复待办。
 * <p>
 * {@code excerpt} 是截断后的片段而非整段对话：工单是给处理人做判断的入口，
 * 需要完整上下文时应回到会话里按权限查看，而不是在工单表里再存一份。
 */
@Data
@TableName("t_review_task")
public class ReviewTask {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String traceId;

    private Long conversationId;

    private Long userId;

    /** 触发时命中的路由，当前恒为 CRISIS。 */
    private String route;

    /** HIGH / MEDIUM */
    private String level;

    /** RULE（分诊规则命中）/ MODEL（情绪评估判为高风险） */
    private String source;

    private String excerpt;

    /** PENDING / CLAIMED / CLOSED / FALSE_POSITIVE */
    private String status;

    private String handler;

    private String conclusion;

    /** 首次响应时限，超时即为逾期。 */
    private LocalDateTime dueAt;

    private LocalDateTime claimedAt;

    private LocalDateTime closedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
