-- ============================================================
-- V1: 初始表结构
-- 约定：
--   1. 主键统一 BIGINT 雪花 ID，由 MyBatis-Plus ASSIGN_ID 生成
--   2. 业务表带 created_at / updated_at，便于按时间排查
--   3. 用户与会话用 deleted 做逻辑删除（可追溯），消息表不做逻辑删除（审计需要，只追加）
-- ============================================================

CREATE TABLE t_user
(
    id            BIGINT       NOT NULL COMMENT '主键（雪花 ID）',
    username      VARCHAR(64)  NOT NULL COMMENT '登录名',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 哈希，永不存明文',
    nickname      VARCHAR(64)           DEFAULT NULL COMMENT '昵称',
    role          VARCHAR(16)  NOT NULL DEFAULT 'USER' COMMENT 'USER / ADMIN',
    status        TINYINT      NOT NULL DEFAULT 1 COMMENT '1 正常 0 禁用',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted       TINYINT      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0 未删 1 已删',
    PRIMARY KEY (id),
    UNIQUE KEY uk_username (username)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '用户';

-- 注意：uk_username 与逻辑删除天然冲突——删除后同名账号无法再注册。
-- 阶段 3 做注册功能时用「删除时把 username 改写为 username#<id>」解决，先记录在此。

CREATE TABLE t_conversation
(
    id              BIGINT      NOT NULL COMMENT '主键（雪花 ID）',
    user_id         BIGINT      NOT NULL COMMENT '归属用户',
    title           VARCHAR(128)         DEFAULT NULL COMMENT '会话标题，首轮对话后由模型摘要生成',
    message_count   INT         NOT NULL DEFAULT 0 COMMENT '消息条数（冗余字段，避免列表页 count）',
    last_message_at DATETIME             DEFAULT NULL COMMENT '最后一条消息时间，会话列表排序用',
    status          TINYINT     NOT NULL DEFAULT 1 COMMENT '1 进行中 0 已关闭',
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    deleted         TINYINT     NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    KEY idx_user_last (user_id, deleted, last_message_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '会话';

CREATE TABLE t_message
(
    id              BIGINT      NOT NULL COMMENT '主键（雪花 ID）',
    conversation_id BIGINT      NOT NULL COMMENT '所属会话',
    user_id         BIGINT      NOT NULL COMMENT '归属用户（冗余，便于按用户维度审计）',
    role            VARCHAR(16) NOT NULL COMMENT 'user / assistant / system',
    content         TEXT        NOT NULL COMMENT '消息内容',
    model           VARCHAR(64)          DEFAULT NULL COMMENT 'assistant 消息使用的模型',
    tokens_in       INT                  DEFAULT NULL,
    tokens_out      INT                  DEFAULT NULL,
    cost_ms         INT                  DEFAULT NULL COMMENT '本次模型调用耗时',
    risk_level      VARCHAR(16)          DEFAULT NULL COMMENT 'NONE / LOW / MEDIUM / HIGH，阶段 6 由情绪评估 Agent 写入',
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_conv_id (conversation_id, id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT '消息（只追加，不逻辑删除）';
