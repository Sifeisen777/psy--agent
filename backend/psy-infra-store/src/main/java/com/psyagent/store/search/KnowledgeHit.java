package com.psyagent.store.search;

import lombok.Data;

/**
 * 检索命中项。
 * <p>
 * {@code score} 来自 MySQL 全文索引的相关度（类 TF-IDF，词频乘以逆文档频率），
 * 量纲与向量余弦相似度不同：它没有固定上界，跨查询不可直接比较，
 * 只适合同一次查询内部排序。
 */
@Data
public class KnowledgeHit {

    private Long id;

    private Long docId;

    private String title;

    private String content;

    private Double score;
}
