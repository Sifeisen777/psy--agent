package com.psyagent.bootstrap.agent;

import com.psyagent.bootstrap.service.KnowledgeRetriever;
import com.psyagent.store.search.KnowledgeHit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识检索节点。
 * <p>
 * 只在非危机场景下运行。危机场景刻意跳过检索：这时候用户需要的是一句直接的关心和一个电话，
 * 不是一篇关于失眠成因的科普。给得越多，越像在回避。
 */
@Component
@Order(20)
public class KnowledgeNode implements AgentNode {

    private static final Logger log = LoggerFactory.getLogger(KnowledgeNode.class);

    private final KnowledgeRetriever retriever;
    private final boolean enabled;

    public KnowledgeNode(KnowledgeRetriever retriever,
                         @Value("${psy.knowledge.enabled:true}") boolean enabled) {
        this.retriever = retriever;
        this.enabled = enabled;
    }

    @Override
    public String name() {
        return "知识检索";
    }

    @Override
    public boolean supports(AgentContext context) {
        // 危机：用户此刻需要的是一个电话，不是科普；
        // 寒暄：检索必然无匹配，白跑一次查询还会在轨迹里留下噪音
        return enabled
                && context.getRoute() != AgentRoute.CRISIS
                && context.getRoute() != AgentRoute.SMALLTALK;
    }

    @Override
    public void apply(AgentContext context) {
        try {
            context.setHits(retriever.retrieve(context.getMessage()));
        } catch (Exception e) {
            // 检索是增强手段，失败退化为普通对话，不能让请求失败
            log.warn("knowledge node failed, fallback to plain chat", e);
            context.setHits(List.of());
        }
    }

    @Override
    public String detail(AgentContext context) {
        List<KnowledgeHit> hits = context.getHits();
        if (hits.isEmpty()) {
            return "无匹配";
        }
        return hits.size() + " 条：" + hits.get(0).getTitle();
    }
}
