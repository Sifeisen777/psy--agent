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
 * 只在 KNOWLEDGE 路由下运行，其余三类都刻意跳过：危机路径用户需要一句关心和一个电话，
 * 不是一篇关于失眠成因的科普；寒暄没有可检索的内容；情绪倾诉一旦掺进资料编号，
 * 第一句就不再是"我听见你了"，而变成了"这是第 3 条建议"。
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
        // 只在"用户在问方法"时检索。三条排除的理由各不相同：
        // 危机——此刻需要的是一个电话，不是科普；
        // 寒暄——必然无匹配，白跑一次查询还留下噪音；
        // 情绪——检索结果会诱导模型在共情里插资料编号，而情绪支持的第一句必须是回应感受。
        //        这是评测暴露出来的：曾经情绪路径也检索，模型就开始标 [1] 了。
        return enabled && context.getRoute() == AgentRoute.KNOWLEDGE;
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
