package com.psyagent.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 单轮对话的编排轨迹。
 * <p>
 * 刻意不存用户输入与回复正文：观测数据会被更宽松地查询和导出，
 * 正文只在 t_message，靠 conversationId 关联。这是心理数据的最小必要原则。
 */
@Data
@TableName("t_chat_trace")
public class ChatTrace {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String traceId;

    private Long userId;

    private Long conversationId;

    private String route;

    private Integer hitl;

    private String model;

    private Integer mock;

    private Integer ttftMs;

    private Integer totalMs;

    private String nodeCostJson;

    private Integer referenceCount;

    private Integer toolCount;

    private String toolNames;

    private String riskLevel;

    private Integer replyLength;

    private Integer clientGone;

    private LocalDateTime createdAt;
}
