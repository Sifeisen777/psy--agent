package com.psyagent.bootstrap.agent;

/**
 * 本轮对话的路由结果。
 * <p>
 * 四类之间不是并列关系，而是"策略强度"递增：越靠前，对模型的约束越硬。
 * CRISIS 会短路后续节点（不检索、不给自助建议），其余三类共用检索能力，只是提示词侧重不同。
 */
public enum AgentRoute {

    /** 存在自伤、自杀等风险表述：强制给出求助资源，并标记需人工复核。 */
    CRISIS("危机干预"),

    /** 知识型提问：优先依据检索资料作答并标注引用。 */
    KNOWLEDGE("知识问答"),

    /** 情绪困扰：先共情再给少量可执行建议。 */
    EMOTION("情绪支持"),

    /** 寒暄或极短输入：简短回应，自然引导。 */
    SMALLTALK("简短寒暄");

    private final String label;

    AgentRoute(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
