package com.psyagent.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.psyagent.store.entity.ReviewTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Map;

@Mapper
public interface ReviewTaskMapper extends BaseMapper<ReviewTask> {

    /**
     * 认领：条件更新，只有 PENDING 能变成 CLAIMED。
     * <p>
     * 用显式 SQL 而不是 lambdaUpdate：状态机的核心不变量就是这条 `WHERE status='PENDING'`，
     * 它应该一眼可见，而不是藏在链式调用的中间。返回值 0 行即"已被别人认领"。
     */
    @Update("UPDATE t_review_task SET status = 'CLAIMED', handler = #{handler}, claimed_at = NOW() " +
            "WHERE id = #{id} AND status = 'PENDING'")
    int claim(@Param("id") Long id, @Param("handler") String handler);

    /** 关闭：只有认领过才能闭环，避免"没人负责却显示已处理"。 */
    @Update("UPDATE t_review_task SET status = #{status}, conclusion = #{conclusion}, closed_at = NOW() " +
            "WHERE id = #{id} AND status = 'CLAIMED'")
    int close(@Param("id") Long id, @Param("status") String status,
              @Param("conclusion") String conclusion);

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
