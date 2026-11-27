-- ============================================================
-- V3: 工具服务与可观测
-- 设计要点：
--   1. 工具产出（量表、预约）是业务数据，必须独立建表，不能只留在调用日志里；
--   2. t_tool_call 是统一审计，任何入口（编排节点 / MCP / REST）的调用都写同一张表；
--   3. t_chat_trace 一轮一条，trace_id 唯一，避免重试产生重复指标；
--   4. 心理数据不写原文到观测表，只留统计字段（route / 耗时 / 引用数）。
-- ============================================================

CREATE TABLE t_scale_record
(
    id              BIGINT      NOT NULL COMMENT '雪花 ID',
    user_id         BIGINT      NOT NULL,
    conversation_id BIGINT               DEFAULT NULL,
    scale_code      VARCHAR(16) NOT NULL COMMENT 'PHQ9 / GAD7',
    answers         VARCHAR(128) NOT NULL COMMENT '逗号分隔的原始作答，0-3',
    score           INT         NOT NULL,
    severity        VARCHAR(16) NOT NULL COMMENT 'MINIMAL / MILD / MODERATE / SEVERE',
    suggestion      VARCHAR(255)         DEFAULT NULL,
    trace_id        VARCHAR(40)          DEFAULT NULL,
    created_at      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_scale_user (user_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='量表计分记录';

CREATE TABLE t_appointment
(
    id               BIGINT       NOT NULL COMMENT '雪花 ID',
    user_id          BIGINT       NOT NULL,
    conversation_id  BIGINT                DEFAULT NULL,
    contact          VARCHAR(128) NOT NULL COMMENT '联系方式',
    preferred_time   VARCHAR(64)  NOT NULL COMMENT '期望时间',
    topic            VARCHAR(255)          DEFAULT NULL,
    status           VARCHAR(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / CONFIRMED / CANCELLED',
    idempotency_key  VARCHAR(96)           DEFAULT NULL COMMENT '同一 key 重复提交返回同一条记录',
    trace_id         VARCHAR(40)           DEFAULT NULL,
    created_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at       DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_idempotency (idempotency_key),
    KEY idx_appt_user (user_id, created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='咨询预约';

CREATE TABLE t_tool_call
(
    id               BIGINT      NOT NULL COMMENT '雪花 ID',
    trace_id         VARCHAR(40)          DEFAULT NULL,
    user_id          BIGINT               DEFAULT NULL,
    conversation_id  BIGINT               DEFAULT NULL,
    tool_name        VARCHAR(48) NOT NULL,
    source           VARCHAR(16) NOT NULL COMMENT 'AGENT / MCP / REST',
    arguments_json   TEXT,
    result_json      TEXT,
    success          TINYINT     NOT NULL DEFAULT 1,
    error_msg        VARCHAR(255)         DEFAULT NULL,
    cost_ms          INT         NOT NULL DEFAULT 0,
    created_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_tool_name (tool_name, created_at),
    KEY idx_tool_trace (trace_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='工具调用审计，任何入口统一落库';

CREATE TABLE t_chat_trace
(
    id               BIGINT      NOT NULL COMMENT '雪花 ID',
    trace_id         VARCHAR(40) NOT NULL,
    user_id          BIGINT      NOT NULL,
    conversation_id  BIGINT               DEFAULT NULL,
    route            VARCHAR(16) NOT NULL,
    hitl             TINYINT     NOT NULL DEFAULT 0,
    model            VARCHAR(64)          DEFAULT NULL,
    mock             TINYINT     NOT NULL DEFAULT 0,
    ttft_ms          INT                  DEFAULT NULL COMMENT '首字延迟',
    total_ms         INT         NOT NULL,
    node_cost_json   TEXT                 DEFAULT NULL,
    reference_count  INT         NOT NULL DEFAULT 0,
    tool_count       INT         NOT NULL DEFAULT 0,
    tool_names       VARCHAR(255)         DEFAULT NULL,
    risk_level       VARCHAR(16)          DEFAULT NULL,
    reply_length     INT         NOT NULL DEFAULT 0,
    client_gone      TINYINT     NOT NULL DEFAULT 0,
    created_at       DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_trace (trace_id),
    KEY idx_trace_route (route, created_at),
    KEY idx_trace_created (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='单轮对话编排轨迹，指标看板数据源';
