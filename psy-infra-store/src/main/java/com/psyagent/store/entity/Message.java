package com.psyagent.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 消息。
 * <p>
 * 与另外两张表不同：**不做逻辑删除**。消息是审计与回溯的依据，
 * 一旦允许删除，"为什么这个用户被判为高风险"就查不清了。需要清理走归档，不走 delete。
 * <p>
 * {@code riskLevel} 现在恒为 null，阶段 6 由情绪评估 Agent 写入，
 * 阶段 6 的危机干预会直接按 `risk_level in ('MEDIUM','HIGH')` 做后台检索。
 */
@Data
@TableName("t_message")
public class Message {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long conversationId;

    private Long userId;

    /** user / assistant / system */
    private String role;

    private String content;

    private String model;

    private Integer tokensIn;

    private Integer tokensOut;

    private Integer costMs;

    /** NONE / LOW / MEDIUM / HIGH */
    private String riskLevel;

    private LocalDateTime createdAt;
}
