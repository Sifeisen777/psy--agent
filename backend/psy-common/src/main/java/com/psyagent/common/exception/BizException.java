package com.psyagent.common.exception;

import com.psyagent.common.api.ResultCode;

/**
 * 业务异常。
 * <p>
 * 区分"业务可预期失败"（用户输入问题、AI 返回不合规）和"系统故障"（NPE、DB 连不上）。
 * 前者抛它，由全局异常处理器翻译成 200 + 业务码；后者让它冒泡成 500，并触发告警。
 * 这条边界划清楚，日志才不会全是噪音。
 */
public class BizException extends RuntimeException {

    private final ResultCode resultCode;

    public BizException(ResultCode resultCode) {
        super(resultCode.message());
        this.resultCode = resultCode;
    }

    public BizException(ResultCode resultCode, String message) {
        super(message);
        this.resultCode = resultCode;
    }

    public BizException(ResultCode resultCode, String message, Throwable cause) {
        super(message, cause);
        this.resultCode = resultCode;
    }

    public ResultCode getResultCode() {
        return resultCode;
    }

    public static BizException of(ResultCode rc) {
        return new BizException(rc);
    }

    public static BizException of(ResultCode rc, String msg) {
        return new BizException(rc, msg);
    }
}
