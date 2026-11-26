package com.psyagent.common.api;

/**
 * 统一错误码。
 * <p>
 * 约定：业务码 4xxxx，系统码 5xxxx，AI 相关 6xxxx。
 * 错误码是"前后端契约"，前端只认 code，不认 message 文案（文案随时会改）。
 */
public enum ResultCode {

    OK(0, "ok"),

    BAD_REQUEST(40000, "请求参数不合法"),
    UNAUTHORIZED(40100, "未登录或登录已过期"),
    FORBIDDEN(40300, "无权限访问"),
    NOT_FOUND(40400, "资源不存在"),

    INTERNAL_ERROR(50000, "系统繁忙，请稍后重试"),

    RATE_LIMITED(42900, "请求过于频繁，请稍后再试"),

    AI_UNAVAILABLE(60001, "AI 服务不可用"),
    AI_TIMEOUT(60002, "AI 响应超时"),
    AI_OUTPUT_INVALID(60003, "AI 输出格式不合法"),
    AI_AUTH_FAILED(60004, "模型鉴权失败，请检查 API Key");

    private final int code;
    private final String message;

    ResultCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int code() {
        return code;
    }

    public String message() {
        return message;
    }
}
