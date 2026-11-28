package com.psyagent.bootstrap.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.psyagent.bootstrap.agent.AgentContext;
import com.psyagent.common.api.ResultCode;
import com.psyagent.common.exception.BizException;
import com.psyagent.store.entity.ReviewTask;
import com.psyagent.store.mapper.ReviewTaskMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 人工复核工单。
 * <p>
 * 把"危机"从一条日志变成一个有人负责的待办，需要三样东西：**持久化、状态机、时限**。
 * 缺任何一样，所谓的 HITL 都只是响应里的一个布尔值。
 * <p>
 * 状态流转固定为：
 * <pre>
 *   PENDING ──认领──→ CLAIMED ──关闭──→ CLOSED
 *      └────────────关闭──────────────→ FALSE_POSITIVE
 * </pre>
 * 关闭必须走 CAS（`WHERE status = ?`），否则两个人同时点"关闭"会互相覆盖结论，
 * 而这类并发在值班场景里非常常见。
 */
@Service
public class ReviewTaskService {

    private static final Logger log = LoggerFactory.getLogger(ReviewTaskService.class);

    public static final String PENDING = "PENDING";
    public static final String CLAIMED = "CLAIMED";
    public static final String CLOSED = "CLOSED";
    public static final String FALSE_POSITIVE = "FALSE_POSITIVE";

    /** 高风险首次响应时限。超过它，"及时介入"就只是宣传语。 */
    private static final int SLA_MINUTES = 15;
    private static final int EXCERPT_MAX = 120;

    private final ReviewTaskMapper mapper;

    public ReviewTaskService(ReviewTaskMapper mapper) {
        this.mapper = mapper;
    }

    /** 分诊规则命中危机时建单。 */
    public void createForCrisis(AgentContext context) {
        create(context, "RULE");
    }

    /**
     * 情绪评估判为高风险但路由未命中时建单。
     * <p>
     * 这一条是规则的必要补充：规则会漏（用户用隐喻表达），后置评估正好兜住这类漏判。
     */
    public void createForModelHigh(AgentContext context) {
        create(context, "MODEL");
    }

    private void create(AgentContext context, String source) {
        if (context == null || context.getUserId() == null) {
            return;
        }
        ReviewTask task = new ReviewTask();
        task.setTraceId(context.getTraceId());
        task.setConversationId(context.getConversationId());
        task.setUserId(context.getUserId());
        task.setRoute(context.getRoute() == null ? "CRISIS" : context.getRoute().name());
        task.setLevel("HIGH");
        task.setSource(source);
        task.setExcerpt(truncate(context.getMessage()));
        task.setStatus(PENDING);
        task.setDueAt(LocalDateTime.now().plusMinutes(SLA_MINUTES));
        try {
            mapper.insert(task);
            log.warn("review task created traceId={} userId={} source={} dueAt={}",
                    task.getTraceId(), task.getUserId(), source, task.getDueAt());
        } catch (RuntimeException e) {
            // uk_review_trace 冲突 = 同一轮已经建过单（规则与模型都判了高风险），属于预期
            log.debug("review task skipped traceId={} reason={}", task.getTraceId(), e.getMessage());
        }
    }

    public List<ReviewTask> list(String status, int limit) {
        return mapper.selectList(Wrappers.<ReviewTask>lambdaQuery()
                .eq(status != null && !status.isBlank(), ReviewTask::getStatus, status)
                .orderByAsc(ReviewTask::getDueAt)
                .orderByDesc(ReviewTask::getId)
                .last("LIMIT " + Math.max(1, Math.min(200, limit))));
    }

    public Map<String, Object> summary(int hours) {
        Map<String, Object> counts = new LinkedHashMap<>();
        for (String status : List.of(PENDING, CLAIMED, CLOSED, FALSE_POSITIVE)) {
            counts.put(status, 0);
        }
        for (Map<String, Object> row : mapper.statusStats(hours)) {
            counts.put(String.valueOf(row.get("status")), toInt(row.get("cnt")));
        }
        int total = counts.values().stream().mapToInt(v -> (Integer) v).sum();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hours", hours);
        data.put("total", total);
        data.put("counts", counts);
        data.put("overdue", mapper.countOverdue());
        data.put("avgClaimSeconds", mapper.avgClaimSeconds(hours));
        return data;
    }

    /** 认领：只有 PENDING 能被认领，避免两个值班人同时跟进同一个用户。 */
    public ReviewTask claim(Long id, String handler) {
        require(id);
        int rows = mapper.update(null, Wrappers.<ReviewTask>lambdaUpdate()
                .eq(ReviewTask::getId, id)
                .eq(ReviewTask::getStatus, PENDING)
                .set(ReviewTask::getStatus, CLAIMED)
                .set(ReviewTask::getHandler, handler)
                .set(ReviewTask::getClaimedAt, LocalDateTime.now()));
        if (rows == 0) {
            throw BizException.of(ResultCode.BAD_REQUEST, "工单已被认领或已关闭");
        }
        return mapper.selectById(id);
    }

    /**
     * 关闭：结论只有两种，误报与已处理。
     * <p>
     * 误报样本是有价值的：规则误伤的案例应当反哺关键词表，否则同一个误判会一直出现。
     */
    public ReviewTask close(Long id, String status, String conclusion) {
        if (!CLOSED.equals(status) && !FALSE_POSITIVE.equals(status)) {
            throw BizException.of(ResultCode.BAD_REQUEST,
                    "结论只能是 " + CLOSED + " 或 " + FALSE_POSITIVE);
        }
        require(id);
        int rows = mapper.update(null, Wrappers.<ReviewTask>lambdaUpdate()
                .eq(ReviewTask::getId, id)
                .eq(ReviewTask::getStatus, CLAIMED)
                .set(ReviewTask::getStatus, status)
                .set(ReviewTask::getConclusion, conclusion)
                .set(ReviewTask::getClosedAt, LocalDateTime.now()));
        if (rows == 0) {
            throw BizException.of(ResultCode.BAD_REQUEST, "只能关闭已认领的工单，请先认领");
        }
        return mapper.selectById(id);
    }

    private ReviewTask require(Long id) {
        ReviewTask task = mapper.selectById(id);
        if (task == null) {
            throw BizException.of(ResultCode.NOT_FOUND, "工单不存在");
        }
        return task;
    }

    private static String truncate(String text) {
        if (text == null) {
            return "";
        }
        String stripped = text.strip().replaceAll("\\s+", " ");
        return stripped.length() <= EXCERPT_MAX ? stripped : stripped.substring(0, EXCERPT_MAX) + "…";
    }

    private static int toInt(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        return 0;
    }
}
