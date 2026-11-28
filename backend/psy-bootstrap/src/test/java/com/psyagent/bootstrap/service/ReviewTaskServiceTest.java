package com.psyagent.bootstrap.service;

import com.psyagent.bootstrap.agent.AgentContext;
import com.psyagent.bootstrap.agent.AgentRoute;
import com.psyagent.common.api.ResultCode;
import com.psyagent.common.exception.BizException;
import com.psyagent.store.entity.ReviewTask;
import com.psyagent.store.mapper.ReviewTaskMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 工单状态机的单元测试。
 * <p>
 * 重点不在正常路径——正常路径显然能跑通——而在这三条约束：
 * <p>
 * 1. 只有 PENDING 能被认领，重复认领必须失败（防止两人跟进同一个用户）；
 * 2. 只有 CLAIMED 能被关闭，未认领就关闭意味着"没人负责却显示已处理"；
 * 3. 建单失败（同一轮重复触发）不能影响对话主流程。
 * <p>
 * 用假 mapper 而不是真库：状态机是纯逻辑，连库的测试会慢一个数量级，
 * 而且真正的 CAS 语义（`WHERE status = ?`）由数据库保证，测的是"我们是否依赖了返回值"。
 */
@ExtendWith(MockitoExtension.class)
class ReviewTaskServiceTest {

    @Mock
    private ReviewTaskMapper mapper;

    @InjectMocks
    private ReviewTaskService service;

    private static ReviewTask task(Long id, String status) {
        ReviewTask task = new ReviewTask();
        task.setId(id);
        task.setStatus(status);
        task.setTraceId("trace-" + id);
        task.setLevel("HIGH");
        task.setExcerpt("我不想活了");
        return task;
    }

    private static AgentContext crisisContext(String message) {
        AgentContext context = new AgentContext(1L, 10L, message, List.of(), "trace-1");
        context.setRoute(AgentRoute.CRISIS);
        context.setHitlRequired(true);
        return context;
    }

    @Test
    @DisplayName("认领成功：条件更新命中 1 行，返回处理中的工单")
    void claimSuccess() {
        when(mapper.selectById(1L)).thenReturn(task(1L, ReviewTaskService.PENDING),
                task(1L, ReviewTaskService.CLAIMED));
        when(mapper.claim(1L, "staff")).thenReturn(1);

        ReviewTask result = service.claim(1L, "staff");

        assertThat(result.getStatus()).isEqualTo(ReviewTaskService.CLAIMED);
        verify(mapper).claim(1L, "staff");
    }

    @Test
    @DisplayName("重复认领：CAS 未命中（0 行）必须报错")
    void claimTwiceFails() {
        when(mapper.selectById(1L)).thenReturn(task(1L, ReviewTaskService.CLAIMED));
        when(mapper.claim(1L, "staff")).thenReturn(0);

        assertThatThrownBy(() -> service.claim(1L, "staff"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("已被认领");
    }

    @Test
    @DisplayName("工单不存在：返回 40400 而不是空指针")
    void claimMissingTask() {
        when(mapper.selectById(9L)).thenReturn(null);

        assertThatThrownBy(() -> service.claim(9L, "staff"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getResultCode())
                .isEqualTo(ResultCode.NOT_FOUND);
        verify(mapper, never()).claim(any(), any());
    }

    @Test
    @DisplayName("关闭成功")
    void closeSuccess() {
        when(mapper.selectById(1L)).thenReturn(task(1L, ReviewTaskService.CLAIMED),
                task(1L, ReviewTaskService.CLOSED));
        when(mapper.close(1L, ReviewTaskService.CLOSED, "已电话联系，本人安全")).thenReturn(1);

        ReviewTask result = service.close(1L, ReviewTaskService.CLOSED, "已电话联系，本人安全");

        assertThat(result.getStatus()).isEqualTo(ReviewTaskService.CLOSED);
    }

    @Test
    @DisplayName("未认领直接关闭被拒，且不产生更新")
    void closeWithoutClaimFails() {
        when(mapper.selectById(1L)).thenReturn(task(1L, ReviewTaskService.PENDING));
        when(mapper.close(1L, ReviewTaskService.CLOSED, "跳过认领")).thenReturn(0);

        assertThatThrownBy(() -> service.close(1L, ReviewTaskService.CLOSED, "跳过认领"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("请先认领");
    }

    @Test
    @DisplayName("非法结论在触库之前就被拒绝")
    void closeWithInvalidStatusRejectedEarly() {
        assertThatThrownBy(() -> service.close(1L, "DONE", "随便写"))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("只能是");
        verifyNoInteractions(mapper);
    }

    @Test
    @DisplayName("危机建单：状态、来源、时限与片段都符合约定")
    void createForCrisisBuildsTask() {
        LocalDateTime before = LocalDateTime.now();
        service.createForCrisis(crisisContext("我不想活了"));

        ArgumentCaptor<ReviewTask> captor = ArgumentCaptor.forClass(ReviewTask.class);
        verify(mapper).insert(captor.capture());
        ReviewTask saved = captor.getValue();

        assertThat(saved.getStatus()).isEqualTo(ReviewTaskService.PENDING);
        assertThat(saved.getSource()).isEqualTo("RULE");
        assertThat(saved.getLevel()).isEqualTo("HIGH");
        assertThat(saved.getRoute()).isEqualTo("CRISIS");
        assertThat(saved.getTraceId()).isEqualTo("trace-1");
        assertThat(saved.getDueAt()).isAfterOrEqualTo(before.plusMinutes(15))
                .isBefore(before.plusMinutes(16));
    }

    @Test
    @DisplayName("模型兜底建单来源标记为 MODEL")
    void createForModelHighMarksSource() {
        AgentContext context = new AgentContext(1L, 10L, "最近总是很绝望", List.of(), "trace-2");
        context.setRoute(AgentRoute.EMOTION);

        service.createForModelHigh(context);

        ArgumentCaptor<ReviewTask> captor = ArgumentCaptor.forClass(ReviewTask.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getSource()).isEqualTo("MODEL");
    }

    @Test
    @DisplayName("片段超长时截断并加省略号，换行压成空格")
    void excerptIsTruncatedAndFlattened() {
        service.createForCrisis(crisisContext("第一行\n第二行" + "啊".repeat(200)));

        ArgumentCaptor<ReviewTask> captor = ArgumentCaptor.forClass(ReviewTask.class);
        verify(mapper).insert(captor.capture());
        String excerpt = captor.getValue().getExcerpt();

        assertThat(excerpt).doesNotContain("\n").endsWith("…").hasSize(121);
    }

    @Test
    @DisplayName("重复建单（唯一键冲突）被吞掉，不影响对话")
    void duplicateInsertDoesNotPropagate() {
        when(mapper.insert(any(ReviewTask.class)))
                .thenThrow(new RuntimeException("Duplicate entry for key 'uk_review_trace'"));

        service.createForCrisis(crisisContext("我不想活了"));

        verify(mapper).insert(any(ReviewTask.class));
    }

    @Test
    @DisplayName("概况：缺失状态补 0，总数与逾期数一致")
    void summaryFillsMissingStatuses() {
        when(mapper.statusStats(24)).thenReturn(List.of(
                Map.of("status", "PENDING", "cnt", 3L),
                Map.of("status", "CLOSED", "cnt", 1L)));
        when(mapper.countOverdue()).thenReturn(2);
        when(mapper.avgClaimSeconds(24)).thenReturn(45);

        Map<String, Object> summary = service.summary(24);

        assertThat(summary.get("total")).isEqualTo(4);
        @SuppressWarnings("unchecked")
        Map<String, Integer> counts = (Map<String, Integer>) summary.get("counts");
        assertThat(counts).containsEntry(ReviewTaskService.PENDING, 3)
                .containsEntry(ReviewTaskService.CLAIMED, 0)
                .containsEntry(ReviewTaskService.CLOSED, 1)
                .containsEntry(ReviewTaskService.FALSE_POSITIVE, 0);
        assertThat(summary.get("overdue")).isEqualTo(2);
        assertThat(summary.get("avgClaimSeconds")).isEqualTo(45);
    }

    @Test
    @DisplayName("没有会话的同步通道也能建单（人工仍可跟进用户）")
    void createWithoutConversationStillWorks() {
        AgentContext context = new AgentContext(1L, null, "我不想活了", List.of(), "trace-3");
        context.setRoute(AgentRoute.CRISIS);

        service.createForCrisis(context);

        ArgumentCaptor<ReviewTask> captor = ArgumentCaptor.forClass(ReviewTask.class);
        verify(mapper).insert(captor.capture());
        assertThat(captor.getValue().getConversationId()).isNull();
        assertThat(captor.getValue().getUserId()).isEqualTo(1L);
    }

    @Test
    @DisplayName("缺用户标识时直接跳过，不写脏数据")
    void skipWhenUserIdMissing() {
        AgentContext context = new AgentContext(null, 10L, "我不想活了", List.of(), "trace-4");
        context.setRoute(AgentRoute.CRISIS);

        service.createForCrisis(context);

        verify(mapper, never()).insert(any(ReviewTask.class));
    }
}
