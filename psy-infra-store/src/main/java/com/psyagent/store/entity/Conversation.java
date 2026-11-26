package com.psyagent.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话。
 * <p>
 * {@code messageCount} 与 {@code lastMessageAt} 是**冗余字段**：会话列表页只需要这两个值，
 * 若不冗余，每次翻列表都要对 t_message 做 count/max，几十条会话就是几十次聚合。
 * 冗余的代价是写入时要同步维护——阶段 3 落库消息时会在同一事务里 +1 并更新时间。
 */
@Data
@TableName("t_conversation")
public class Conversation {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private String title;

    private Integer messageCount;

    private LocalDateTime lastMessageAt;

    /** 1 进行中 0 已关闭 */
    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @TableLogic
    private Integer deleted;
}
