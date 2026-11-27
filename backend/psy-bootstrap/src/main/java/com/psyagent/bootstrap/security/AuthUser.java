package com.psyagent.bootstrap.security;

/**
 * 已认证用户，作为 SecurityContext 的 principal。
 * <p>
 * 只放鉴权需要的最小信息，不携带实体对象，避免在请求生命周期内误用过期数据。
 */
public record AuthUser(Long id, String username) {
}
