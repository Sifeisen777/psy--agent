package com.psyagent.infra.ai;

import java.time.Instant;

/**
 * 模型可用性探测结果。
 * <p>
 * {@code llmAvailable} 曾经只表示"配了 Key"，错误 Key 下依然报 UP —— 健康检查在撒谎。
 * 探活要回答的是另一个问题：**现在拿这个 Key 发一次请求，能不能成功**。
 *
 * @param ok       是否可用
 * @param state    OK / MOCK / AUTH_FAILED / ERROR
 * @param detail   失败原因摘要（不含任何密钥片段）
 * @param costMs   探活耗时
 * @param checkedAt 本次探测时间（结果会缓存，客户端据此判断新鲜度）
 */
public record ProbeResult(boolean ok, String state, String detail, long costMs, Instant checkedAt) {

    public static ProbeResult ok(long costMs) {
        return new ProbeResult(true, "OK", null, costMs, Instant.now());
    }

    public static ProbeResult mock() {
        return new ProbeResult(false, "MOCK", "未配置模型 Key，当前为 Mock 模式", 0, Instant.now());
    }

    public static ProbeResult authFailed(long costMs) {
        return new ProbeResult(false, "AUTH_FAILED", "API Key 无效或无权限", costMs, Instant.now());
    }

    public static ProbeResult error(String detail, long costMs) {
        return new ProbeResult(false, "ERROR", detail, costMs, Instant.now());
    }
}
