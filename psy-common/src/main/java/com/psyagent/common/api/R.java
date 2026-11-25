package com.psyagent.common.api;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/**
 * 统一响应体。
 * <p>
 * 为什么不用裸 DTO：前端拦截器需要一个稳定的外层结构来判断成功/失败、弹提示、做埋点。
 * {@code traceId} 现在先占位，阶段 6 接 OpenTelemetry 后由 MDC 注入，前端报错截图能直接定位链路。
 *
 * @param code    0 表示成功
 * @param message 给人看的提示，不要在前端做逻辑判断
 * @param data    业务数据
 * @param ts      服务端时间戳，便于排查客户端时钟漂移
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record R<T>(int code, String message, T data, long ts) {

    public static <T> R<T> ok(T data) {
        return new R<>(ResultCode.OK.code(), ResultCode.OK.message(), data, Instant.now().toEpochMilli());
    }

    public static R<Void> ok() {
        return ok(null);
    }

    public static <T> R<T> fail(ResultCode rc) {
        return new R<>(rc.code(), rc.message(), null, Instant.now().toEpochMilli());
    }

    public static <T> R<T> fail(ResultCode rc, String message) {
        return new R<>(rc.code(), message, null, Instant.now().toEpochMilli());
    }

    public boolean success() {
        return code == ResultCode.OK.code();
    }
}
