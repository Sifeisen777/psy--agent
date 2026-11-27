package com.psyagent.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.psyagent.store.entity.KnowledgeDoc;
import org.apache.ibatis.annotations.Mapper;

/**
 * 知识库文档。
 */
@Mapper
public interface KnowledgeDocMapper extends BaseMapper<KnowledgeDoc> {
}
