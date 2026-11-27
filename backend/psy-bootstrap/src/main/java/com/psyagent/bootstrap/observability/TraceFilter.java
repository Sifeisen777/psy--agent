package com.psyagent.bootstrap.observability;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 为每个请求分配 traceId，并写进日志 MDC 与响应头。
 * <p>
 * 两个刻意的设计：
 * <p>
 * 1. **接受上游传入的 traceId**（网关 / 压测脚本可指定），但只接受白名单字符，
 *    避免把任意内容注入日志；不合法就重新生成。
 * 2. **响应头回写**：前端与压测脚本拿到它就能把"用户看到的慢"和"后端这条链路"对上，
 *    不用去翻日志猜时间。
 * <p>
 * 注意 MDC 只在请求线程有效，异步回调里的取值走 TraceContext + 显式传递。
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {

    /** 只接受字母数字与短横线/下划线，长度 8-64，防止日志注入。 */
    private static final Pattern VALID = Pattern.compile("[A-Za-z0-9_-]{8,64}");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String incoming = request.getHeader(TraceContext.HEADER);
        String traceId = incoming != null && VALID.matcher(incoming).matches()
                ? incoming
                : UUID.randomUUID().toString().replace("-", "").substring(0, 24);

        MDC.put(TraceContext.MDC_KEY, traceId);
        response.setHeader(TraceContext.HEADER, traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            MDC.remove(TraceContext.MDC_KEY);
        }
    }
}
