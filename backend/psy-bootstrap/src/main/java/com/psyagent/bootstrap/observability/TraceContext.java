package com.psyagent.bootstrap.observability;

import org.slf4j.MDC;

/**
 * traceId 的统一读取口。
 * <p>
 * 只保证**请求线程**里能读到：SSE 生成回调跑在 Reactor / 弹性线程池上，MDC 不会自动继承。
 * 因此编排层在构造上下文时把 traceId 取出来存进 {@code AgentContext}，后续全部显式传递，
 * 不依赖线程池上下文传播——那东西在异步边界上最不可靠。
 */
public final class TraceContext {

    public static final String HEADER = "X-Trace-Id";
    public static final String MDC_KEY = "traceId";
    public static final String NONE = "no-trace";

    private TraceContext() {
    }

    public static String current() {
        String value = MDC.get(MDC_KEY);
        return value == null || value.isBlank() ? NONE : value;
    }
}
