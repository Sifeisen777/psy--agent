package com.psyagent.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/** 量表计分记录。作答原文只存 0-3 的分数序列，不存自由文本。 */
@Data
@TableName("t_scale_record")
public class ScaleRecord {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long userId;

    private Long conversationId;

    private String scaleCode;

    private String answers;

    private Integer score;

    private String severity;

    private String suggestion;

    private String traceId;

    private LocalDateTime createdAt;
}
