package com.psyagent.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.psyagent.store.entity.ChatTrace;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface ChatTraceMapper extends BaseMapper<ChatTrace> {

    /** 按路由聚合：轮次、平均首字延迟、需人工复核数。 */
    @Select("SELECT route, COUNT(*) AS cnt, ROUND(AVG(ttft_ms)) AS avg_ttft, SUM(hitl) AS hitl_cnt " +
            "FROM t_chat_trace WHERE created_at >= DATE_SUB(NOW(), INTERVAL #{hours} HOUR) GROUP BY route")
    List<Map<String, Object>> routeStats(@Param("hours") int hours);

    /** 有序返回首字延迟样本，Java 侧算分位数，避免绑定数据库方言。 */
    @Select("SELECT ttft_ms FROM t_chat_trace WHERE ttft_ms IS NOT NULL " +
            "AND created_at >= DATE_SUB(NOW(), INTERVAL #{hours} HOUR) ORDER BY ttft_ms")
    List<Integer> recentTtft(@Param("hours") int hours);

    @Select("SELECT COUNT(*) FROM t_chat_trace " +
            "WHERE created_at >= DATE_SUB(NOW(), INTERVAL #{hours} HOUR)")
    int countRecent(@Param("hours") int hours);

    /** 看板明细：最近若干轮，不含正文。 */
    @Select("SELECT trace_id, route, hitl, mock, ttft_ms, total_ms, reference_count, " +
            "tool_names, risk_level, client_gone, created_at " +
            "FROM t_chat_trace ORDER BY created_at DESC LIMIT #{limit}")
    List<Map<String, Object>> recentTraces(@Param("limit") int limit);

    /** 按小时聚合，用于趋势折线。 */
    @Select("SELECT DATE_FORMAT(created_at, '%Y-%m-%d %H:00') AS bucket, COUNT(*) AS cnt, " +
            "SUM(CASE WHEN route = 'CRISIS' THEN 1 ELSE 0 END) AS crisis_cnt " +
            "FROM t_chat_trace WHERE created_at >= DATE_SUB(NOW(), INTERVAL #{hours} HOUR) " +
            "GROUP BY bucket ORDER BY bucket")
    List<Map<String, Object>> hourlyTrend(@Param("hours") int hours);

    /** 单行汇总指标。无数据时 AVG/SUM 为 NULL，由 Java 侧归一为 0。 */
    @Select("SELECT COUNT(*) AS cnt, SUM(hitl) AS hitl_cnt, SUM(mock) AS mock_cnt, " +
            "SUM(client_gone) AS gone_cnt, ROUND(AVG(total_ms)) AS avg_total, " +
            "ROUND(AVG(ttft_ms)) AS avg_ttft " +
            "FROM t_chat_trace WHERE created_at >= DATE_SUB(NOW(), INTERVAL #{hours} HOUR)")
    Map<String, Object> summary(@Param("hours") int hours);
}
