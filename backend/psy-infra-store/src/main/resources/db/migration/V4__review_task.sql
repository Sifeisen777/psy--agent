-- ============================================================
-- V4：人工复核工单
--
-- 背景：
--   此前危机场景只做两件事——在响应里返回 hitl=true、打一条 WARN 日志。
--   那是"信号"，不是"流程"：刷新页面就没了，没人能跟进，也无法统计响应时效。
--
-- 设计要点：
--   1. 一单一 trace：同一轮对话重复触发不会产生重复工单（uk_review_trace）；
--   2. 记录 due_at：没有时限的待办等于没有待办，HIGH 默认 15 分钟内首次响应；
--   3. 状态机固定四态：PENDING / CLAIMED / CLOSED / FALSE_POSITIVE。
--      没有 FALSE_POSITIVE，规则误判会让队列全是噪音，最后没人看；
--   4. excerpt 只存截断片段，不复制整段对话——工单是用来跟进的，不是第二份聊天记录。
--
-- 同时修正一处索引设计：
--   t_chat_trace.trace_id 原本是唯一索引，客户端带同一个 X-Trace-Id 重试时，
--   第二次写入会失败并被静默丢弃，看板因此少算重试次数、成功率虚高。
--   唯一约束应加在"业务主键"上，而不是"链路标识"上——一次链路本就可以有多次尝试。
-- ============================================================

CREATE TABLE t_review_task
(
    id              BIGINT       NOT NULL COMMENT '雪花 ID',
    trace_id        VARCHAR(40)  NOT NULL COMMENT '关联 t_chat_trace，一单一链路',
    conversation_id BIGINT                DEFAULT NULL,
    user_id         BIGINT       NOT NULL,
    route           VARCHAR(16)  NOT NULL DEFAULT 'CRISIS',
    level           VARCHAR(16)  NOT NULL DEFAULT 'HIGH' COMMENT 'HIGH / MEDIUM',
    source          VARCHAR(16)  NOT NULL COMMENT 'RULE 规则命中 / MODEL 模型评估',
    excerpt         VARCHAR(255) NOT NULL COMMENT '触发片段，截断保存',
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING / CLAIMED / CLOSED / FALSE_POSITIVE',
    handler         VARCHAR(64)           DEFAULT NULL COMMENT '处理人用户名',
    conclusion      VARCHAR(255)          DEFAULT NULL COMMENT '处理结论',
    due_at          DATETIME     NOT NULL COMMENT '首次响应时限',
    claimed_at      DATETIME              DEFAULT NULL,
    closed_at       DATETIME              DEFAULT NULL,
    created_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_review_trace (trace_id),
    KEY idx_review_status (status, due_at),
    KEY idx_review_created (created_at)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4 COMMENT ='人工复核工单：危机场景的跟进闭环';

ALTER TABLE t_chat_trace
    DROP INDEX uk_trace,
    ADD KEY idx_trace_trace_id (trace_id);
