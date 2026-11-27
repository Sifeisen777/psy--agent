package com.psyagent.bootstrap.agent;

import com.psyagent.bootstrap.service.KnowledgeRetriever;
import com.psyagent.infra.ai.EmotionAssessment;
import com.psyagent.infra.ai.RuleBasedEmotionJudge;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 分诊节点：决定本轮走哪条策略。
 * <p>
 * 用规则而不是再调一次模型分类，理由有三：
 * <p>
 * 1. **延迟**：分类若走模型要再加 300ms，而路由必须发生在生成之前，这段延迟直接叠在首字上；
 * 2. **可解释**：规则命中哪一条，日志里写得清楚，出问题能复盘；
 * 3. **安全优先**：风险判定不能依赖概率。规则命中"不想活"就一定是 CRISIS，
 *    模型可能因为上下文判断成"用户在讨论别人"而放行，这种误判代价太大。
 * <p>
 * 规则覆盖不到的细微差别交给生成阶段——同一个模型，不同提示词，效果差异足够明显。
 */
@Component
@Order(10)
public class TriageNode implements AgentNode {

    /** 寒暄长度上限：超过这个长度基本不是寒暄，而是有内容要表达。 */
    private static final int SMALLTALK_MAX_LEN = 6;

    /**
     * 疑问标记：出现即认为用户在求信息，而不是单纯倾诉。
     * <p>
     * 刻意排除了"什么 / 哪些 / 哪种"这类泛词：它们在陈述句里同样是高频成分，
     * 例如"我今天特别难过，什么都不想说"——含"什么"但完全没有提问意图，
     * 判成知识问答会让提示词走成"给方法"，而这时候用户需要的是先被听见。
     */
    private static final List<String> QUESTION_MARKS = List.of(
            "怎么", "如何", "为什么", "怎么办", "是不是", "能不能", "该不该",
            "有没有", "要不要", "多少", "请问", "吗"
    );

    @Override
    public String name() {
        return "分诊";
    }

    @Override
    public boolean supports(AgentContext context) {
        return context.getRoute() == null;
    }

    @Override
    public void apply(AgentContext context) {
        String message = context.getMessage();
        context.setRoute(route(message));
    }

    @Override
    public String detail(AgentContext context) {
        return context.getRoute() == null ? "" : context.getRoute().label();
    }

    /**
     * 路由判定。顺序即优先级：风险 > 知识 > 寒暄 > 情绪。
     * 风险必须最先判，因为一旦命中，后面的检索引擎与建议策略都不该再参与。
     */
    static AgentRoute route(String message) {
        if (message == null || message.isBlank()) {
            return AgentRoute.SMALLTALK;
        }
        EmotionAssessment rule = RuleBasedEmotionJudge.judge(message);
        if (EmotionAssessment.RISK_HIGH.equals(rule.riskLevel())) {
            return AgentRoute.CRISIS;
        }

        String compact = message.replaceAll("[\\s，。！？、,.!?~…]", "");
        boolean inDomain = KnowledgeRetriever.inDomain(message);
        boolean asking = QUESTION_MARKS.stream().anyMatch(message::contains);

        if (compact.length() <= SMALLTALK_MAX_LEN && !inDomain) {
            return AgentRoute.SMALLTALK;
        }
        if (inDomain && asking) {
            return AgentRoute.KNOWLEDGE;
        }
        return AgentRoute.EMOTION;
    }
}
