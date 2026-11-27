package com.psyagent.bootstrap.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/**
 * JWT 签发与解析。
 * <p>
 * 采用 HS256 对称签名：单体部署下密钥只存在于服务端，无需 JWKS 分发。
 * 若后续拆分为多服务，可切换为 RS256，公钥分发给调用方。
 */
@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);
    private static final String CLAIM_USERNAME = "username";

    private final JwtProperties properties;
    private final SecretKey key;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        this.key = Keys.hmacShaKeyFor(properties.secret().getBytes(StandardCharsets.UTF_8));
    }

    public String issue(Long userId, String username) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(String.valueOf(userId))
                .claim(CLAIM_USERNAME, username)
                .issuer(properties.issuer())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.expiration())))
                .signWith(key)
                .compact();
    }

    /**
     * 解析并校验令牌。签名错误、过期、格式非法统一返回 empty，
     * 由调用方决定是拒绝请求还是忽略（匿名访问）。
     */
    public Optional<AuthUser> parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(new AuthUser(Long.valueOf(claims.getSubject()),
                    claims.get(CLAIM_USERNAME, String.class)));
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("jwt parse failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    public long expirationSeconds() {
        return properties.expiration().toSeconds();
    }
}
