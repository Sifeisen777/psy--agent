package com.psyagent.bootstrap.tool;

import com.psyagent.store.mapper.ChatTraceMapper;
import com.psyagent.store.mapper.ToolCallMapper;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 运行统计：把 t_chat_trace / t_tool_call 聚合成可回答"系统现在怎么样"的指标。
 * <p>
 * 这个工具存在的意义不是给前端看板用（前端走专门的 metrics 接口），
 * 而是让**模型客户端**也能通过一次工具调用拿到系统状态——
 * 例如"今天有多少危机会话""首字延迟 p95 是多少"。
 * <p>
 * p50/p95 在 Java 侧算而不写 SQL 窗口函数：数据量小、逻辑直白，
 * 且换数据库时不用改方言。
 */
@Component
public class ChatStatsTool implements AgentTool {

    private final ChatTraceMapper chatTraceMapper;
    private final ToolCallMapper toolCallMapper;

    public ChatStatsTool(ChatTraceMapper chatTraceMapper, ToolCallMapper toolCallMapper) {
        this.chatTraceMapper = chatTraceMapper;
        this.toolCallMapper = toolCallMapper;
    }

    @Override
    public String name() {
        return "chat_stats";
    }

    @Override
    public String description() {
        return "查询系统最近 N 小时的运行统计：对话总量、四类路由分布、危机会话数、"
                + "首字延迟 p50/p95、工具调用次数与成功率。用于回答系统运行状况类问题。";
    }

    @Override
    public Map<String, Object> inputSchema() {
        Map<String, Object> hours = new LinkedHashMap<>();
        hours.put("type", "integer");
        hours.put("minimum", 1);
        hours.put("maximum", 720);
        hours.put("description", "统计时间窗口（小时），默认 24");

        Map<String, Object> root = new LinkedHashMap<>();
        root.put("type", "object");
        root.put("properties", Map.of("hours", hours));
        return root;
    }

    @Override
    public ToolResult execute(Map<String, Object> args, ToolContext context) {
        int hours = Math.max(1, Math.min(720, ToolArgs.intVal(args, "hours", 24)));
        try {
            List<Map<String, Object>> routeRows = chatTraceMapper.routeStats(hours);
            List<Integer> ttft = chatTraceMapper.recentTtft(hours);
            List<Map<String, Object>> toolRows = toolCallMapper.toolStats(hours);
            int totalChats = chatTraceMapper.countRecent(hours);

            List<Map<String, Object>> routes = new ArrayList<>();
            int crisisCount = 0;
            for (Map<String, Object> row : routeRows) {
                Map<String, Object> item = new LinkedHashMap<>();
                String route = String.valueOf(row.get("route"));
                int count = num(row.get("cnt"));
                item.put("route", route);
                item.put("count", count);
                item.put("avgTtftMs", num(row.get("avg_ttft")));
                int hitl = num(row.get("hitl_cnt"));
                item.put("hitl", hitl);
                routes.add(item);
                if ("CRISIS".equals(route)) {
                    crisisCount = count;
                }
            }

            List<Map<String, Object>> tools = new ArrayList<>();
            for (Map<String, Object> row : toolRows) {
                Map<String, Object> item = new LinkedHashMap<>();
                int count = num(row.get("cnt"));
                item.put("tool", String.valueOf(row.get("tool_name")));
                item.put("count", count);
                item.put("success", num(row.get("ok_cnt")));
                item.put("avgCostMs", num(row.get("avg_cost")));
                tools.add(item);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("hours", hours);
            data.put("totalChats", totalChats);
            data.put("crisisChats", crisisCount);
            data.put("routes", routes);
            data.put("ttftSamples", ttft.size());
            data.put("ttftP50Ms", percentile(ttft, 50));
            data.put("ttftP95Ms", percentile(ttft, 95));
            data.put("tools", tools);
            data.put("generatedAt", LocalDateTime.now().toString());

            String summary = "最近 " + hours + " 小时共 " + totalChats + " 轮对话，危机 " + crisisCount
                    + " 轮；首字延迟 p50=" + data.get("ttftP50Ms") + "ms，p95="
                    + data.get("ttftP95Ms") + "ms；工具调用 " + tools.size() + " 类。";
            return ToolResult.ok(summary, data);
        } catch (RuntimeException e) {
            return ToolResult.fail("统计查询失败：" + e.getMessage());
        }
    }

    private static int percentile(List<Integer> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    /** 聚合查询返回的数字类型随驱动变化（Long / BigDecimal / Integer），统一转 int。 */
    private static int num(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return (int) Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
