package com.psyagent.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.psyagent.store.entity.ReviewTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Map;

@Mapper
public interface ReviewTaskMapper extends BaseMapper<ReviewTask> {

    /** 按状态聚合，用于复核队列的头部数字。 */
    @Select("SELECT status, COUNT(*) AS cnt FROM t_review_task " +
            "WHERE created_at >= DATE_SUB(NOW(), INTERVAL #{hours} HOUR) GROUP BY status")
    List<Map<String, Object>> statusStats(int hours);

    /** 未闭环且已超过首次响应时限的数量：这是唯一能证明"有人在跟"的指标。 */
    @Select("SELECT COUNT(*) FROM t_review_task " +
            "WHERE status IN ('PENDING','CLAIMED') AND due_at < NOW()")
    int countOverdue();

    /** 平均首次响应耗时（秒），只统计已认领的工单。 */
    @Select("SELECT ROUND(AVG(TIMESTAMPDIFF(SECOND, created_at, claimed_at))) " +
            "FROM t_review_task WHERE claimed_at IS NOT NULL " +
            "AND created_at >= DATE_SUB(NOW(), INTERVAL #{hours} HOUR)")
    Integer avgClaimSeconds(int hours);
}
