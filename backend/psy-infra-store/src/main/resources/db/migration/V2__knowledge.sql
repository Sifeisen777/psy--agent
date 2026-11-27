-- 知识库：文档与切片
-- 检索采用 MySQL ngram 全文索引（中文按 2 字切分），属于稀疏检索，
-- 与向量检索同属 RAG 的检索层，差异只在相似度度量方式。

CREATE TABLE IF NOT EXISTS t_knowledge_doc
(
    id          BIGINT       NOT NULL COMMENT '雪花 ID',
    title       VARCHAR(160) NOT NULL COMMENT '文档标题',
    category    VARCHAR(32)  NOT NULL COMMENT '分类：sleep / anxiety / study / emotion / crisis',
    source      VARCHAR(160)          DEFAULT NULL COMMENT '来源说明',
    content     MEDIUMTEXT   NOT NULL COMMENT '原文',
    chunk_count INT          NOT NULL DEFAULT 0 COMMENT '切片数量',
    status      TINYINT      NOT NULL DEFAULT 1 COMMENT '1 启用 0 停用',
    created_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at  DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_category (category)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='知识库文档';

CREATE TABLE IF NOT EXISTS t_knowledge_chunk
(
    id         BIGINT       NOT NULL COMMENT '雪花 ID',
    doc_id     BIGINT       NOT NULL COMMENT '所属文档',
    chunk_index INT         NOT NULL COMMENT '文档内顺序，从 0 开始',
    title      VARCHAR(160) NOT NULL COMMENT '冗余文档标题，检索结果免关联',
    content    TEXT         NOT NULL COMMENT '切片正文',
    char_count INT          NOT NULL DEFAULT 0,
    created_at DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_doc (doc_id),
    FULLTEXT KEY ft_content (content) WITH PARSER ngram
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='知识库切片，content 建 ngram 全文索引';
