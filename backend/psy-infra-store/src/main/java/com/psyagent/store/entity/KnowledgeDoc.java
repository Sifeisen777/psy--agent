package com.psyagent.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库文档。
 * <p>
 * 原文整体保留，切片单独存表：检索命中的是切片，但引用溯源需要回到原文，
 * 两者职责分开比在一个大字段里反复切分更清晰。
 */
@Data
@TableName("t_knowledge_doc")
public class KnowledgeDoc {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String title;

    private String category;

    private String source;

    private String content;

    private Integer chunkCount;

    private Integer status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
