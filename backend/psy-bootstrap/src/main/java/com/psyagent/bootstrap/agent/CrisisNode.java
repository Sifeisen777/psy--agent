package com.psyagent.bootstrap.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * 危机干预节点。
 * <p>
 * 这个节点几乎不"做事"——它标记人工复核、写一条告警日志、并把策略交给提示词。
 * 看起来单薄，但这三件事缺一不可：
 * <p>
 * - **标记**：让后台能筛出需要跟进的会话，而不是等事情发生后再翻聊天记录；
 * - **告警**：高风险日志单独一个级别，便于接入监控。混在普通日志里等于没有；
 * - **策略**：迫使生成阶段放弃"给建议"的惯性，改为直接给求助资源。
 */
@Component
@Order(30)
public class CrisisNode implements AgentNode {

    private static final Logger log = LoggerFactory.getLogger(CrisisNode.class);

    @Override
    public String name() {
        return "危机干预";
    }

    @Override
    public boolean supports(AgentContext context) {
        return context.getRoute() == AgentRoute.CRISIS;
    }

    @Override
    public void apply(AgentContext context) {
        context.setHitlRequired(true);
        context.setHits(java.util.List.of());
        // 单独一条告警日志：生产环境应对这条接入实时通知，而不是等人来查
        log.warn("crisis detected, human review required userId={} conversationId={}",
                context.getUserId(), context.getConversationId());
    }

    @Override
    public String detail(AgentContext context) {
        return "已标记人工复核";
    }
}
