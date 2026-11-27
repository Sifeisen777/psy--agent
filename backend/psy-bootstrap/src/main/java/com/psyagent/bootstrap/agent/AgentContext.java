package com.psyagent.bootstrap.agent;

import com.psyagent.store.search.KnowledgeHit;
import org.springframework.ai.chat.messages.Message;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 编排上下文：一次对话在节点之间传递的唯一载体。
 * <p>
 * 刻意做成可变对象：每个节点往同一个上下文里写自己的产出（路由、检索结果、提示词）。
 * 用不可变对象会让每个节点都返回新副本，而收益为零——这个对象只活在一次请求内，不会外泄。
 */
public class AgentContext {

    private final Long userId;
    private final Long conversationId;
    private final String message;
    private final List<Message> history;
    private final List<AgentStep> trace = new ArrayList<>();

    /** 路由结果，由 TriageNode 写入。 */
    private AgentRoute route;

    /** 检索命中，由 KnowledgeNode 写入。 */
    private List<KnowledgeHit> hits = List.of();

    /** 是否需要人工复核，由 CrisisNode 写入。 */
    private boolean hitlRequired;

    /** 拼装好的系统提示，由编排器在全部节点执行后写入。 */
    private String systemPrompt;

    public AgentContext(Long userId, Long conversationId, String message, List<Message> history) {
        this.userId = userId;
        this.conversationId = conversationId;
        this.message = message;
        this.history = history == null ? List.of() : history;
    }

    public Long getUserId() {
        return userId;
    }

    public Long getConversationId() {
        return conversationId;
    }

    public String getMessage() {
        return message;
    }

    public List<Message> getHistory() {
        return history;
    }

    public AgentRoute getRoute() {
        return route;
    }

    public void setRoute(AgentRoute route) {
        this.route = route;
    }

    public List<KnowledgeHit> getHits() {
        return hits;
    }

    public void setHits(List<KnowledgeHit> hits) {
        this.hits = hits == null ? List.of() : hits;
    }

    public boolean isHitlRequired() {
        return hitlRequired;
    }

    public void setHitlRequired(boolean hitlRequired) {
        this.hitlRequired = hitlRequired;
    }

    public String getSystemPrompt() {
        return systemPrompt;
    }

    public void setSystemPrompt(String systemPrompt) {
        this.systemPrompt = systemPrompt;
    }

    public List<AgentStep> getTrace() {
        return trace;
    }

    public void addStep(String node, String detail, int costMs) {
        trace.add(new AgentStep(node, detail, costMs));
    }

    /** 交给模型的参考资料文本，带标题便于模型理解出处。 */
    public List<String> referenceTexts() {
        return hits.stream().map(h -> h.getTitle() + "：" + h.getContent()).toList();
    }

    /** 返回给前端的引用标识，只给编号与标题。 */
    public List<Map<String, Object>> citations() {
        List<Map<String, Object>> citations = new ArrayList<>(hits.size());
        for (int i = 0; i < hits.size(); i++) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("index", i + 1);
            item.put("title", hits.get(i).getTitle());
            item.put("docId", hits.get(i).getDocId());
            citations.add(item);
        }
        return citations;
    }

    /**
     * 单个节点的执行记录。
     *
     * @param node   节点名
     * @param detail 该节点的判定结论，例如路由结果、命中篇数
     * @param costMs 节点耗时
     */
    public record AgentStep(String node, String detail, int costMs) {
    }
}
