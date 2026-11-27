package com.psyagent.bootstrap.agent;

import com.psyagent.infra.ai.AiProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.annotation.AnnotationAwareOrderComparator;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 编排器：按顺序执行节点，最后拼装系统提示。
 * <p>
 * 没有引入工作流引擎或 Agent 框架，因为这一层的本质只有两件事：
 * **按条件执行节点** 和 **把各节点产出汇总成一份提示词**。
 * 引入框架会多一层抽象、一套版本约束和一份需要额外学习的 DSL，
 * 而换来的能力（可视化编排、动态改图）在当前的固定业务流程里用不上。
 * <p>
 * 节点顺序由 {@code @Order} 决定，编排器不写死顺序：
 * 新增节点只需加一个 Bean，路由与汇总逻辑都不用改。
 */
@Service
public class AgentOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(AgentOrchestrator.class);

    private final List<AgentNode> nodes;
    private final AiProperties aiProperties;

    public AgentOrchestrator(List<AgentNode> nodes, AiProperties aiProperties) {
        List<AgentNode> ordered = new ArrayList<>(nodes);
        AnnotationAwareOrderComparator.sort(ordered);
        this.nodes = List.copyOf(ordered);
        this.aiProperties = aiProperties;
    }

    /**
     * 执行一轮编排。单个节点异常不中断流程：
     * 例如检索挂了仍应继续生成，否则一次检索故障会变成整个对话不可用。
     */
    public void run(AgentContext context) {
        for (AgentNode node : nodes) {
            if (!node.supports(context)) {
                continue;
            }
            long start = System.currentTimeMillis();
            try {
                node.apply(context);
            } catch (RuntimeException e) {
                log.error("agent node failed name={} userId={}", node.name(), context.getUserId(), e);
                continue;
            }
            int cost = (int) (System.currentTimeMillis() - start);
            context.addStep(node.name(), node.detail(context), cost);
        }
        context.setSystemPrompt(composeSystem(context));
        if (log.isDebugEnabled()) {
            log.debug("agent chain route={} steps={}", context.getRoute(), context.getTrace());
        }
    }

    /** 人设 + 路由策略 + 参考资料，三段的顺序不能变：越靠后越具体，模型对末尾的遵循度更高。 */
    private String composeSystem(AgentContext context) {
        StringBuilder sb = new StringBuilder(aiProperties.systemPrompt());
        sb.append("\n\n【本轮判定：").append(context.getRoute().label()).append("】\n");
        sb.append(strategy(context.getRoute()));

        List<String> references = context.referenceTexts();
        if (!references.isEmpty()) {
            sb.append("\n【参考资料】以下是与本轮问题相关的知识片段，按编号排列：\n");
            for (int i = 0; i < references.size(); i++) {
                sb.append('[').append(i + 1).append("] ").append(references.get(i)).append('\n');
            }
            sb.append("""
                    【引用要求】
                    1. 优先依据参考资料作答；资料未覆盖的部分可以给通用建议，但不得编造成资料中的内容；
                    2. 引用到某条资料时，在该句末尾标注编号，如 [1]；
                    3. 多条资料重复时合并表达，不要逐条复述；
                    4. 保持自然口语，不必每句都带编号。
                    """);
        }
        return sb.toString();
    }

    /**
     * 各类路由的生成策略。这是"多智能体"的实际形态：
     * 模型是同一个，差别全部体现在约束上。
     */
    private static String strategy(AgentRoute route) {
        return switch (route) {
            case CRISIS -> """
                    用户可能正处于危险中。本轮必须遵守：
                    1. 第一句直接表达关心与陪伴，不做任何分析、归因或说教；
                    2. 必须明确给出全国心理援助热线 12356，并提示紧急情况拨打 120；
                    3. 不提供自助练习、不列举多条建议、不询问原因细节；
                    4. 总长度不超过 120 字，语气平稳，不要用感叹号堆砌情绪。
                    """;
            case KNOWLEDGE -> """
                    用户在询问方法或信息。本轮要求：
                    1. 先一句话回应处境，再给方法，方法要具体到能今天执行；
                    2. 有参考资料时优先采用，并标注编号；
                    3. 不给诊断结论，涉及用药与就医时只建议寻求专业评估。
                    """;
            case EMOTION -> """
                    用户在表达情绪，不是提问。本轮要求：
                    1. 先准确回应感受，再给建议，不要跳过共情直接给方案；
                    2. 建议最多两条，每条一句话，具体可执行；
                    3. 不追问原因，不要求用户复述细节。
                    """;
            case SMALLTALK -> """
                    用户在做简短交流。本轮要求：
                    1. 一两句回应即可，不要长篇；
                    2. 自然地把话题引向对方当下的状态，但不要像问卷一样连问多个问题。
                    """;
        };
    }
}
