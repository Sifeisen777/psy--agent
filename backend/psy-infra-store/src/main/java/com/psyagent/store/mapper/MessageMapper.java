package com.psyagent.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.psyagent.store.entity.Message;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MessageMapper extends BaseMapper<Message> {

    /**
     * 统计会话在时间窗内出现过的高风险用户消息。
     * <p>
     * 只查 user 角色的 risk_level：这里要回答的是"这个人最近说过什么"，
     * 而不是"系统当时怎么想"，所以不把 assistant 的评估算进来。
     */
    @Select("SELECT COUNT(*) FROM t_message " +
            "WHERE conversation_id = #{conversationId} AND role = 'user' " +
            "AND risk_level = 'HIGH' AND created_at >= DATE_SUB(NOW(), INTERVAL #{hours} HOUR)")
    int countRecentHighRisk(@Param("conversationId") Long conversationId,
                            @Param("hours") int hours);
}
