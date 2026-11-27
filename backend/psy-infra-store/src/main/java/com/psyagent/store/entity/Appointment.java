package com.psyagent.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 咨询预约。idempotencyKey 保证重复提交不会产生两条待确认记录。 */
@Data
@TableName("t_appointment")
public class Appointment {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long conversationId;

    private String contact;

    private String preferredTime;

    private String topic;

    private String status;

    private String idempotencyKey;

    private String traceId;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
