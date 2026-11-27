package com.psyagent.store.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 知识库切片。
 * <p>
 * {@code title} 是有意的冗余：检索结果直接返回标题用于引用标注，
 * 不必为了一个字符串再查一次文档表。切片内容在入库后不再变更，
 * 冗余字段不会产生同步负担。
 */
@Data
@TableName("t_knowledge_chunk")
public class KnowledgeChunk {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long docId;

    private Integer chunkIndex;

    private String title;

    private String content;

    private Integer charCount;

    private LocalDateTime createdAt;
}
