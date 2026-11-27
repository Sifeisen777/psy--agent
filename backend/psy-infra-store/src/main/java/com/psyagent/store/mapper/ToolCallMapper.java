package com.psyagent.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.psyagent.store.entity.ToolCall;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface ToolCallMapper extends BaseMapper<ToolCall> {

    @Select("SELECT tool_name, COUNT(*) AS cnt, SUM(success) AS ok_cnt, ROUND(AVG(cost_ms)) AS avg_cost " +
            "FROM t_tool_call WHERE created_at >= DATE_SUB(NOW(), INTERVAL #{hours} HOUR) GROUP BY tool_name")
    List<Map<String, Object>> toolStats(@Param("hours") int hours);
}
