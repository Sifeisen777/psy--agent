package com.psyagent.store.ratelimit;

import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 基于 Redis 的固定窗口限流。
 * <p>
 * 为什么必须用 Lua：`INCR` 与 `EXPIRE` 若分两次发，中间进程崩了就会产生一个永不过期的计数键，
 * 该用户会被永久限流。Lua 在 Redis 内单线程执行，天然原子。
 * <p>
 * 为什么用固定窗口而不是滑动窗口：实现简单、一次往返。代价是窗口边界可能放行 2 倍流量
 * （59 秒打满 10 次，60 秒再打满 10 次）。对话接口用 60 秒窗口，这个误差可接受；
 * 若将来做付费接口，再换滑动窗口（ZSET 或令牌桶）。
 */
@Component
public class RedisRateLimiter {

    private static final DefaultRedisScript<Long> INCR_WITH_TTL = new DefaultRedisScript<>("""
            local current = redis.call('INCR', KEYS[1])
            if current == 1 then
                redis.call('EXPIRE', KEYS[1], ARGV[2])
            end
            if current > tonumber(ARGV[1]) then
                return 0
            end
            return 1
            """, Long.class);

    private final StringRedisTemplate redis;

    public RedisRateLimiter(StringRedisTemplate redis) {
        this.redis = redis;
    }

    /**
     * 尝试获取一次配额。
     *
     * @param key           限流键（建议带业务前缀与用户维度）
     * @param limit         窗口内允许的最大次数
     * @param windowSeconds 窗口长度（秒）
     * @return true 放行
     */
    public boolean tryAcquire(String key, int limit, int windowSeconds) {
        Long result = redis.execute(INCR_WITH_TTL, List.of(key),
                String.valueOf(limit), String.valueOf(windowSeconds));
        return result != null && result == 1L;
    }

    /** 当前窗口已用次数，用于响应头或调试。 */
    public long currentCount(String key) {
        String value = redis.opsForValue().get(key);
        return value == null ? 0L : Long.parseLong(value);
    }
}
