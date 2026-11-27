package com.psyagent.bootstrap.security;

import com.psyagent.common.api.ResultCode;
import com.psyagent.common.exception.BizException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前登录用户上下文。
 * <p>
 * 业务代码通过此入口获取用户标识，不再从请求参数读取，
 * 避免客户端伪造 userId 造成越权访问。
 */
public final class CurrentUser {

    private CurrentUser() {
    }

    public static AuthUser get() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof AuthUser user)) {
            throw BizException.of(ResultCode.UNAUTHORIZED);
        }
        return user;
    }

    public static Long id() {
        return get().id();
    }
}
