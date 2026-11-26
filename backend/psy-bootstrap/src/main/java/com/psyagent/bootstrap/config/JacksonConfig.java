package com.psyagent.bootstrap.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 把 Long 统一序列化成字符串。
 * <p>
 * 原因：主键是雪花 ID（19 位），远超 JS 的 {@code Number.MAX_SAFE_INTEGER}（9007199254740991）。
 * JSON 里以数字返回时，前端 {@code JSON.parse} 会四舍五入：
 * <pre>
 * 2125804467377700865  →  2125804467377700900
 * </pre>
 * 结果就是"能登录但发消息 404 用户不存在"这种极难排查的 bug。
 * <p>
 * 只处理包装类型 {@code Long}，不动基本类型 {@code long}（如耗时 costMs 仍是数字），
 * 避免把不该变的数值也变成字符串。
 * <p>
 * 入参方向不需要特殊处理：Jackson 默认允许把字符串 "123..." 反序列化进 Long 字段。
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> builder.serializerByType(Long.class, ToStringSerializer.instance);
    }
}
