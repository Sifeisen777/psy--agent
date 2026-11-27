package com.psyagent.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.psyagent.store.entity.KnowledgeChunk;
import com.psyagent.store.search.KnowledgeHit;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 知识切片检索。
 * <p>
 * 用 MySQL 全文索引而非 LIKE：`LIKE '%关键词%'` 无法走索引，只能全表扫描，
 * 且不支持相关度排序与多词组合。全文索引会建立倒排表，并把命中程度算成分值。
 */
@Mapper
public interface KnowledgeChunkMapper extends BaseMapper<KnowledgeChunk> {

    /**
     * 按相关度检索切片。
     *
     * @param query 查询串，多个词以空格分隔，语义为「任一命中」
     * @param limit 返回条数
     */
    @Select("""
            SELECT id,
                   doc_id AS docId,
                   title,
                   content,
                   MATCH(content) AGAINST(#{query} IN NATURAL LANGUAGE MODE) AS score
            FROM t_knowledge_chunk
            WHERE MATCH(content) AGAINST(#{query} IN NATURAL LANGUAGE MODE)
            ORDER BY score DESC
            LIMIT #{limit}
            """)
    List<KnowledgeHit> search(@Param("query") String query, @Param("limit") int limit);
}
