package com.psyagent.bootstrap.controller;

import com.psyagent.bootstrap.agent.AgentRoute;
import com.psyagent.common.api.R;
import com.psyagent.store.mapper.ChatTraceMapper;
import com.psyagent.store.mapper.ToolCallMapper;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 可观测看板数据。
 * <p>
 * 与 chat_stats 工具的区别：工具是给**模型客户端**用的一句话结论 + 结构化数据，
 * 这里给**人**看，所以补齐了零值路由、百分比、趋势序列与明细。
 * 两者查的是同一批表，因此数字必须对得上——这也是一次低成本的交叉校验。
 * <p>
 * 不引 Prometheus / Grafana：单体项目里，把 t_chat_trace 查出来画成图已经能回答
 * "路由分布如何、首字多快、危机多少、工具是否被用到"。
 * 真上生产再接 OTel Exporter，数据模型不用改。
 */
@RestController
@RequestMapping("/api/metrics")
public class MetricsController {

    private final ChatTraceMapper chatTraceMapper;
    private final ToolCallMapper toolCallMapper;

    public MetricsController(ChatTraceMapper chatTraceMapper, ToolCallMapper toolCallMapper) {
        this.chatTraceMapper = chatTraceMapper;
        this.toolCallMapper = toolCallMapper;
    }

    @GetMapping("/overview")
    public R<Map<String, Object>> overview(@RequestParam(defaultValue = "24") int hours) {
        int window = Math.max(1, Math.min(720, hours));
        Map<String, Object> summary = chatTraceMapper.summary(window);
        int total = num(summary == null ? null : summary.get("cnt"));

        // 四类路由全部列出，没有数据的补 0：前端不用处理"某类缺失"，
        // 也不会把"没有危机"误显示成"没统计"
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (AgentRoute route : AgentRoute.values()) {
            counts.put(route.name(), 0);
        }
        for (Map<String, Object> row : chatTraceMapper.routeStats(window)) {
            counts.put(String.valueOf(row.get("route")), num(row.get("cnt")));
        }
        List<Map<String, Object>> routes = new ArrayList<>();
        for (AgentRoute route : AgentRoute.values()) {
            int count = counts.getOrDefault(route.name(), 0);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("route", route.name());
            item.put("label", route.label());
            item.put("count", count);
            item.put("pct", total == 0 ? 0 : Math.round(count * 1000.0 / total) / 10.0);
            routes.add(item);
        }

        List<Integer> ttft = chatTraceMapper.recentTtft(window);
        List<Map<String, Object>> tools = new ArrayList<>();
        for (Map<String, Object> row : toolCallMapper.toolStats(window)) {
            int count = num(row.get("cnt"));
            int success = num(row.get("ok_cnt"));
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tool", String.valueOf(row.get("tool_name")));
            item.put("count", count);
            item.put("success", success);
            item.put("successRate", count == 0 ? 0 : Math.round(success * 1000.0 / count) / 10.0);
            item.put("avgCostMs", num(row.get("avg_cost")));
            tools.add(item);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("hours", window);
        data.put("totalChats", total);
        data.put("hitlCount", num(summary == null ? null : summary.get("hitl_cnt")));
        data.put("mockCount", num(summary == null ? null : summary.get("mock_cnt")));
        data.put("clientGoneCount", num(summary == null ? null : summary.get("gone_cnt")));
        data.put("avgTotalMs", num(summary == null ? null : summary.get("avg_total")));
        data.put("avgTtftMs", num(summary == null ? null : summary.get("avg_ttft")));
        data.put("ttftP50Ms", percentile(ttft, 50));
        data.put("ttftP95Ms", percentile(ttft, 95));
        data.put("ttftSamples", ttft.size());
        data.put("routes", routes);
        data.put("tools", tools);
        data.put("trendHours", trendHours(window));
        data.put("trend", buildTrend(window));
        return R.ok(data);
    }

    /** 趋势最多 48 个点：再长的窗口按天看更合适，逐小时画出来只是一片密集锯齿。 */
    private static int trendHours(int window) {
        return Math.min(window, 48);
    }

    /**
     * 补齐零值小时桶。
     * <p>
     * 数据库只会返回"有数据"的小时。如果直接把查询结果给前端，
     * 刚跑完一轮测试时折线图只有一个点，看起来像"趋势功能坏了"。
     * 观测数据必须显式表达"这一小时是 0"，而不是"这一小时没统计"。
     */
    private List<Map<String, Object>> buildTrend(int window) {
        int points = trendHours(window);
        Map<String, Map<String, Object>> byBucket = new HashMap<>();
        for (Map<String, Object> row : chatTraceMapper.hourlyTrend(points)) {
            byBucket.put(String.valueOf(row.get("bucket")), row);
        }
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:00");
        LocalDateTime currentHour = LocalDateTime.now()
                .withMinute(0).withSecond(0).withNano(0);

        List<Map<String, Object>> trend = new ArrayList<>(points);
        for (int i = points - 1; i >= 0; i--) {
            String bucket = currentHour.minusHours(i).format(formatter);
            Map<String, Object> row = byBucket.get(bucket);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("bucket", bucket);
            item.put("cnt", row == null ? 0 : num(row.get("cnt")));
            item.put("crisis_cnt", row == null ? 0 : num(row.get("crisis_cnt")));
            trend.add(item);
        }
        return trend;
    }

    @GetMapping("/traces")
    public R<List<Map<String, Object>>> traces(@RequestParam(defaultValue = "20") int limit) {
        return R.ok(chatTraceMapper.recentTraces(Math.max(1, Math.min(100, limit))));
    }

    private static int percentile(List<Integer> sorted, int p) {
        if (sorted.isEmpty()) {
            return 0;
        }
        int index = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

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
