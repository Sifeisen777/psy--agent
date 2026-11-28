package com.psyagent.bootstrap.agent;

import com.psyagent.store.mapper.MessageMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * 风险回溯节点的行为约束。
 * <p>
 * 这层最容易写错的地方是"过度反应"：一旦把最近出现过高风险等同于永久危机，
 * 用户说"我好多了"还会被推热线，等于否定他的改善。
 * 所以测试同时锁住两侧：既要把寒暄抬起来，也不能把知识问答压下去。
 */
@ExtendWith(MockitoExtension.class)
class RiskHistoryNodeTest {

    private static final int LOOKBACK_HOURS = 24;

    @Mock
    private MessageMapper messageMapper;

    private RiskHistoryNode node;

    @BeforeEach
    void setUp() {
        node = new RiskHistoryNode(messageMapper, LOOKBACK_HOURS);
    }

    private static AgentContext context(AgentRoute route) {
        AgentContext context = new AgentContext(1L, 10L, "我好点了", List.of(), "trace-1");
        context.setRoute(route);
        return context;
    }

    @Test
    @DisplayName("没有会话历史时不参与，避免无意义的查询")
    void skipWhenNoConversation() {
        AgentContext context = new AgentContext(1L, null, "我好点了", List.of(), "trace-1");
        context.setRoute(AgentRoute.EMOTION);

        assertThat(node.supports(context)).isFalse();
    }

    @Test
    @DisplayName("危机路径已由危机节点处置，不再重复回溯")
    void skipOnCrisisRoute() {
        assertThat(node.supports(context(AgentRoute.CRISIS))).isFalse();
    }

    @Test
    @DisplayName("普通会话参与回溯")
    void supportForNonCrisisRoute() {
        assertThat(node.supports(context(AgentRoute.EMOTION))).isTrue();
    }

    @Test
    @DisplayName("近期出现过高风险时，寒暄被抬为情绪支持并注入语气约束")
    void floorsSmalltalkToEmotion() {
        when(messageMapper.countRecentHighRisk(10L, LOOKBACK_HOURS)).thenReturn(1);
        AgentContext context = context(AgentRoute.SMALLTALK);

        node.apply(context);

        assertThat(context.getRoute()).isEqualTo(AgentRoute.EMOTION);
        assertThat(context.getPriorHighRiskCount()).isEqualTo(1);
        assertThat(context.getGuidance()).hasSize(1);
        assertThat(context.getGuidance().get(0)).contains("高风险").contains("不要当作已经没事");
    }

    @Test
    @DisplayName("知识问答不被降级，只追加语气约束")
    void keepsKnowledgeRoute() {
        when(messageMapper.countRecentHighRisk(10L, LOOKBACK_HOURS)).thenReturn(2);
        AgentContext context = context(AgentRoute.KNOWLEDGE);

        node.apply(context);

        assertThat(context.getRoute()).isEqualTo(AgentRoute.KNOWLEDGE);
        assertThat(context.getGuidance()).isNotEmpty();
    }

    @Test
    @DisplayName("没有高风险记录时不改动路由，也不注入提示")
    void doesNothingWithoutHistory() {
        when(messageMapper.countRecentHighRisk(10L, LOOKBACK_HOURS)).thenReturn(0);
        AgentContext context = context(AgentRoute.SMALLTALK);

        node.apply(context);

        assertThat(context.getRoute()).isEqualTo(AgentRoute.SMALLTALK);
        assertThat(context.getGuidance()).isEmpty();
        assertThat(context.getPriorHighRiskCount()).isZero();
    }

    @Test
    @DisplayName("查询失败不影响本轮对话，按无历史处理")
    void lookupFailureIsSwallowed() {
        when(messageMapper.countRecentHighRisk(10L, LOOKBACK_HOURS))
                .thenThrow(new RuntimeException("db down"));
        AgentContext context = context(AgentRoute.SMALLTALK);

        node.apply(context);

        assertThat(context.getRoute()).isEqualTo(AgentRoute.SMALLTALK);
        assertThat(context.getGuidance()).isEmpty();
        assertThat(context.getPriorHighRiskCount()).isEqualTo(-1);
    }

    @Test
    @DisplayName("detail 复用 apply 的查询结果，不再二次查库")
    void detailUsesCachedCount() {
        when(messageMapper.countRecentHighRisk(10L, LOOKBACK_HOURS)).thenReturn(3);
        AgentContext context = context(AgentRoute.EMOTION);

        node.apply(context);

        assertThat(node.detail(context)).isEqualTo("近 24 小时高风险 3 条");
    }
}
