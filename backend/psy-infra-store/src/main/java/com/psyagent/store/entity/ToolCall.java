package com.psyagent.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 工具调用审计。
 * <p>
 * 三个入口（编排节点 / MCP / REST）共用一张表，才能回答"这个工具今天被谁、从哪里、调了多少次"。
 * 失败也记录：静默失败的工具比报错更危险。
 */
@Data
@TableName("t_tool_call")
public class ToolCall {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String traceId;

    private Long userId;

    private Long conversationId;

    private String toolName;

    private String source;

    private String argumentsJson;

    private String resultJson;

    private Integer success;

    private String errorMsg;

    private Integer costMs;

    private LocalDateTime createdAt;
}
