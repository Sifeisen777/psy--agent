package com.psyagent.bootstrap.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.psyagent.common.api.ResultCode;
import com.psyagent.common.exception.BizException;
import com.psyagent.store.entity.User;
import com.psyagent.store.mapper.UserMapper;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 账号服务。
 * <p>
 * 当前版本尚未接入身份认证，接口由调用方传入 userId。
 * 引入鉴权后，userId 必须从访问令牌解析，避免客户端伪造身份越权访问他人会话。
 */
@Service
public class AuthService {

    private final UserMapper userMapper;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    public AuthService(UserMapper userMapper) {
        this.userMapper = userMapper;
    }

    /**
     * 注册。
     * <p>
     * 只查未删除用户是否重名：已注销用户的 username 已被改写为 {@code name#id}，
     * 所以同名注册天然可行，不需要额外处理。
     */
    public Long register(String username, String rawPassword) {
        Long exists = userMapper.selectCount(
                Wrappers.<User>lambdaQuery().eq(User::getUsername, username));
        if (exists != null && exists > 0) {
            throw BizException.of(ResultCode.BAD_REQUEST, "用户名已存在");
        }
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(encoder.encode(rawPassword));
        user.setNickname(username);
        user.setRole("USER");
        user.setStatus(1);
        userMapper.insert(user);
        return user.getId();
    }

    /**
     * 注销（方案 A）：逻辑删除 + 用户名改写，释放用户名。
     */
    public void unregister(Long userId) {
        int rows = userMapper.softDeleteAndRename(userId);
        if (rows == 0) {
            throw BizException.of(ResultCode.NOT_FOUND, "用户不存在或已注销");
        }
    }

    public User getById(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            throw BizException.of(ResultCode.NOT_FOUND, "用户不存在");
        }
        return user;
    }
}
