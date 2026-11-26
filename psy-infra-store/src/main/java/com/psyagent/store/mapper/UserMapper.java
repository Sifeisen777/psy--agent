package com.psyagent.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.psyagent.store.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 用户 Mapper。
 * <p>
 * 继承 {@link BaseMapper} 即获得 17 个单表方法（selectById / selectList / insert / updateById ...），
 * 复杂的多表查询再写 XML 或 @Select，不要为了"统一"把所有查询都手写。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {

    /**
     * 注销用户（方案 A）：逻辑删除的同时把 username 改写成 {@code name#id}。
     * <p>
     * 为什么不用 MyBatis-Plus 的 deleteById：{@code @TableLogic} 只会把 deleted 置 1，
     * username 保持不变，唯一键 uk_username 会挡住同名用户重新注册。
     * 改写后既保留了"这个账号曾经存在"的审计痕迹，又释放了用户名。
     * <p>
     * 注意：这条 SQL 是**故意手写**的，绕过 @TableLogic 的自动逻辑删除。
     *
     * @return 受影响行数，0 表示用户不存在或已注销
     */
    @Update("""
            UPDATE t_user
               SET deleted    = 1,
                   username   = CONCAT(username, '#', id),
                   updated_at = NOW()
             WHERE id = #{id}
               AND deleted = 0
            """)
    int softDeleteAndRename(@Param("id") Long id);
}
