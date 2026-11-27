package com.psyagent.infra.ai;

import java.time.Instant;

/**
 * 模型可用性探测结果。
 * <p>
 * {@code llmAvailable} 仅表示"已配置密钥"，密钥无效时仍返回 UP，无法反映真实可用性。
 * 探活关注实际可调用状态：使用当前密钥发起一次请求是否成功。
 *
 * @param ok       是否可用
 * @param state    OK / MOCK / AUTH_FAILED / ERROR
 * @param detail   失败原因摘要（不含任何密钥片段）
 * @param costMs   探活耗时。使用 int 而非 long：全局 Long→String 策略会将该字段转为字符串，
 *                 但耗时属于数值语义，应保持数字类型。
 * @param checkedAt 本次探测时间（结果会缓存，客户端据此判断新鲜度）
 */
public record ProbeResult(boolean ok, String state, String detail, int costMs, Instant checkedAt) {

    public static ProbeResult ok(int costMs) {
        return new ProbeResult(true, "OK", null, costMs, Instant.now());
    }

    public static ProbeResult mock() {
        return new ProbeResult(false, "MOCK", "未配置模型 Key，当前为 Mock 模式", 0, Instant.now());
    }

    public static ProbeResult authFailed(int costMs) {
        return new ProbeResult(false, "AUTH_FAILED", "API Key 无效或无权限", costMs, Instant.now());
    }

    public static ProbeResult error(String detail, int costMs) {
        return new ProbeResult(false, "ERROR", detail, costMs, Instant.now());
    }
}
