package com.psyagent.bootstrap.security;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

/**
 * JWT 配置。
 *
 * @param secret     签名密钥，HS256 要求至少 32 字节；生产环境通过环境变量注入
 * @param expiration 令牌有效期
 * @param issuer     签发者标识
 */
@ConfigurationProperties(prefix = "psy.jwt")
public record JwtProperties(String secret, Duration expiration, String issuer) {
}
