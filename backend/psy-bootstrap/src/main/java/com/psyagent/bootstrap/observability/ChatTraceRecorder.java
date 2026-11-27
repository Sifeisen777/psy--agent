package com.psyagent.bootstrap.observability;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.psyagent.bootstrap.agent.AgentContext;
import com.psyagent.store.entity.ChatTrace;
import com.psyagent.store.mapper.ChatTraceMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 单轮轨迹落库。
 * <p>
 * 由 Controller 在**所有终止路径**上调用（正常完成 / 客户端断开 / 超时 / 异常），
 * 靠调用方的 CAS 保证只写一次。原因：只要有一条路径漏写，
 * 看板上的成功率就会虚高，而恰恰是失败的那部分最需要被看见。
 * <p>
 * 本类自身吞掉所有异常：观测链路绝不能反过来影响对话。
 */
@Service
public class ChatTraceRecorder {

    private static final Logger log = LoggerFactory.getLogger(ChatTraceRecorder.class);

    private final ChatTraceMapper mapper;
    private final ObjectMapper objectMapper;

    public ChatTraceRecorder(ChatTraceMapper mapper, ObjectMapper objectMapper) {
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    public void record(AgentContext context, String model, boolean mock, int totalMs,
                       Integer ttftMs, String riskLevel, int replyLength, boolean clientGone) {
        try {
            ChatTrace trace = new ChatTrace();
            trace.setTraceId(context.getTraceId());
            trace.setUserId(context.getUserId() == null ? 0L : context.getUserId());
            trace.setConversationId(context.getConversationId());
            trace.setRoute(context.getRoute() == null ? "UNKNOWN" : context.getRoute().name());
            trace.setHitl(context.isHitlRequired() ? 1 : 0);
            trace.setModel(model);
            trace.setMock(mock ? 1 : 0);
            trace.setTtftMs(ttftMs);
            trace.setTotalMs(totalMs);
            trace.setNodeCostJson(toJson(context.getTrace()));
            trace.setReferenceCount(context.getHits().size());
            trace.setToolCount(context.getToolExecutions().size());
            trace.setToolNames(truncate(context.toolNames(), 255));
            trace.setRiskLevel(riskLevel);
            trace.setReplyLength(replyLength);
            trace.setClientGone(clientGone ? 1 : 0);
            mapper.insert(trace);
        } catch (Exception e) {
            // trace_id 唯一键冲突属于重复终止回调，属于预期情况
            log.debug("chat trace skipped traceId={} reason={}", context.getTraceId(), e.getMessage());
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "[]";
        }
    }

    private static String truncate(String text, int max) {
        return text == null || text.length() <= max ? text : text.substring(0, max);
    }
}
