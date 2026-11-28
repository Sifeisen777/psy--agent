package com.psyagent.bootstrap.advice;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.psyagent.common.api.R;
import com.psyagent.common.api.ResultCode;
import com.psyagent.common.exception.BizException;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * 全局异常处理。
 * <p>
 * 原则：
 * 1. 业务异常 → HTTP 200 + 业务码（前端拦截器统一弹错，不区分 HTTP 语义）；
 * 2. 参数校验 → HTTP 200 + 40000 + 具体字段提示；
 * 3. 未知异常 → HTTP 500 + 50000，日志打全栈，**不把堆栈返回给前端**（信息泄露）。
 * <p>
 * 为什么直接写 {@link HttpServletResponse} 而不返回对象：
 * 流式接口声明了 {@code produces = text/event-stream}，客户端也带着对应的 Accept。
 * 若这里返回一个 Java 对象，Spring 会按请求的 Accept 做内容协商，找不到能把 JSON
 * 序列化成 text/event-stream 的转换器，直接抛 {@code HttpMediaTypeNotAcceptableException}。
 * 结果就是"会话不存在"这类业务错误被包装成 500，前端看到的是"系统繁忙"，
 * 日志里却是一次内容协商失败，排查方向完全被带偏。
 * <p>
 * 显式设置 JSON 响应类型并直接写出，等于把"错误响应的格式"从内容协商里摘出来：
 * 无论请求要的是 SSE 还是 JSON，错误永远是可解析的 JSON。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final ObjectMapper objectMapper;

    public GlobalExceptionHandler(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @ExceptionHandler(BizException.class)
    public void handleBiz(BizException e, HttpServletResponse response) throws IOException {
        log.warn("biz error code={} msg={}", e.getResultCode().code(), e.getMessage());
        write(response, HttpServletResponse.SC_OK, e.getResultCode(), e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public void handleValidation(MethodArgumentNotValidException e, HttpServletResponse response)
            throws IOException {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(FieldError::getDefaultMessage)
                .collect(Collectors.joining("; "));
        write(response, HttpServletResponse.SC_OK, ResultCode.BAD_REQUEST, detail);
    }

    /** 请求体不是合法 JSON：属于客户端问题，不应记成 500。 */
    @ExceptionHandler(HttpMessageNotReadableException.class)
    public void handleUnreadable(HttpMessageNotReadableException e, HttpServletResponse response)
            throws IOException {
        log.warn("malformed request body: {}", e.getMessage());
        write(response, HttpServletResponse.SC_OK, ResultCode.BAD_REQUEST, "请求体格式错误");
    }

    @ExceptionHandler(Exception.class)
    public void handleUnknown(Exception e, HttpServletResponse response) throws IOException {
        log.error("unexpected error", e);
        write(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                ResultCode.INTERNAL_ERROR, null);
    }

    /**
     * @param message 为 null 时使用错误码自带文案，避免把内部异常信息透给前端
     */
    private void write(HttpServletResponse response, int status, ResultCode code, String message)
            throws IOException {
        // 响应已提交（例如 SSE 推送中途失败）时再写只会抛 IllegalStateException
        if (response.isCommitted()) {
            log.debug("response already committed, skip error body code={}", code.code());
            return;
        }
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(), R.fail(code, message));
    }
}
