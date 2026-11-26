package com.psyagent.store.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.psyagent.store.entity.User;
import org.apache.ibatis.annotations.Mapper;

/**
 * 用户 Mapper。
 * <p>
 * 继承 {@link BaseMapper} 即获得 17 个单表方法（selectById / selectList / insert / updateById ...），
 * 复杂的多表查询再写 XML 或 @Select，不要为了"统一"把所有查询都手写。
 */
@Mapper
public interface UserMapper extends BaseMapper<User> {
}
