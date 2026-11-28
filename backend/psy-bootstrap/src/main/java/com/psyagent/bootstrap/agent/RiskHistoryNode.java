package com.psyagent.bootstrap.agent;

import com.psyagent.store.mapper.MessageMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 风险回溯节点：本轮分诊不看历史，但风险不能随一句"我好点了"就被抹掉。
 * <p>
 * 解决的是**状态**与**标记**混淆的问题：
 * <ul>
 *   <li>路由描述的是"这句话"，可以被降级；</li>
 *   <li>风险标记描述的是"这个人最近的状态"，不能被清空。</li>
 * </ul>
 * 所以这里不做永久 CRISIS——那会让用户说"我好多了"时还在被推热线，
 * 等于否定他的改善。做法只有两条：
 * <p>
 * 1. 把寒暄**抬到情绪支持**（"我好点了"只有四个字，按规则会判成寒暄，
 *    走"一两句轻松回应"的策略，这正是最不该出现的反应）；
 * 2. 注入一条语气约束，提醒生成阶段别把"没事了"当成真的没事。
 * <p>
 * 只喂"近期是否出现高风险"这一个布尔结论，不把历史对话拼进规则判断：
 * 拼进去会带来上下文污染（先聊失眠再聊工作，路由永远被拉回睡眠），
 * 也会让分诊结果无法复盘。
 */
@Component
@Order(15)
public class RiskHistoryNode implements AgentNode {

    private static final Logger log = LoggerFactory.getLogger(RiskHistoryNode.class);

    private final MessageMapper messageMapper;
    private final int lookbackHours;

    public RiskHistoryNode(MessageMapper messageMapper,
                           @Value("${psy.risk.lookback-hours:24}") int lookbackHours) {
        this.messageMapper = messageMapper;
        this.lookbackHours = lookbackHours;
    }

    @Override
    public String name() {
        return "风险回溯";
    }

    @Override
    public boolean supports(AgentContext context) {
        // CRISIS 已经由危机节点全权处置，不需要再回溯；
        // 同步通道没有会话，也就没有历史可查
        return context.getConversationId() != null && context.getRoute() != null
                && context.getRoute() != AgentRoute.CRISIS;
    }

    @Override
    public void apply(AgentContext context) {
        int count;
        try {
            count = messageMapper.countRecentHighRisk(context.getConversationId(), lookbackHours);
        } catch (RuntimeException e) {
            // 回溯是增强，查不到就按"无历史"处理，不能影响本轮对话
            log.warn("risk history lookup failed conversationId={}", context.getConversationId(), e);
            return;
        }
        context.setPriorHighRiskCount(count);
        if (count <= 0) {
            return;
        }
        if (context.getRoute() == AgentRoute.SMALLTALK) {
            context.setRoute(AgentRoute.EMOTION);
        }
        context.addGuidance("本会话近 " + lookbackHours + " 小时内出现过高风险表达。"
                + "即使本轮语气轻松，也不要当作已经没事：先确认他的状态，再自然推进，"
                + "不要用轻快的语气回应。");
    }

    @Override
    public String detail(AgentContext context) {
        int count = context.getPriorHighRiskCount();
        if (count < 0) {
            return "未查询";
        }
        return count == 0 ? "近 " + lookbackHours + " 小时无高风险"
                : "近 " + lookbackHours + " 小时高风险 " + count + " 条";
    }
}
