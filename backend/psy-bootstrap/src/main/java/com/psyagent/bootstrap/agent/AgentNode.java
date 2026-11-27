package com.psyagent.bootstrap.agent;

/**
 * 编排节点。
 * <p>
 * 一个节点只做一件事，并且自己决定"这一轮要不要我参与"（{@link #supports}）。
 * 这样编排器不需要知道任何业务规则：加一个新节点，只加一个 Bean，路由逻辑不用改。
 */
public interface AgentNode {

    /** 节点名，会出现在返回给前端的执行轨迹里。 */
    String name();

    /** 本轮是否需要执行。 */
    boolean supports(AgentContext context);

    /** 执行，产出写回上下文。 */
    void apply(AgentContext context);

    /** 执行结论，用于轨迹展示，默认取节点名。 */
    default String detail(AgentContext context) {
        return "";
    }
}
