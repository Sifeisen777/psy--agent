package com.psyagent.bootstrap.service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.psyagent.common.api.ResultCode;
import com.psyagent.common.exception.BizException;
import com.psyagent.store.entity.User;
import com.psyagent.store.mapper.UserMapper;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 账号服务。
 * <p>
 * 密码只以 BCrypt 哈希形式存储，认证过程不区分"用户不存在"与"密码错误"，
 * 避免通过响应差异枚举账号。
 */
@Service
public class AuthService {

    private final UserMapper userMapper;
    private final PasswordEncoder passwordEncoder;

    public AuthService(UserMapper userMapper, PasswordEncoder passwordEncoder) {
        this.userMapper = userMapper;
        this.passwordEncoder = passwordEncoder;
    }

    /**
     * 注册。已注销用户的 username 已被改写为 {@code name#id}，因此同名注册可直接通过。
     */
    public Long register(String username, String rawPassword) {
        Long exists = userMapper.selectCount(
                Wrappers.<User>lambdaQuery().eq(User::getUsername, username));
        if (exists != null && exists > 0) {
            throw BizException.of(ResultCode.BAD_REQUEST, "用户名已存在");
        }
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(rawPassword));
        user.setNickname(username);
        user.setRole("USER");
        user.setStatus(1);
        userMapper.insert(user);
        return user.getId();
    }

    /**
     * 校验用户名与密码。
     *
     * @return 认证通过的用户
     * @throws BizException 凭据错误或账号被禁用
     */
    public User authenticate(String username, String rawPassword) {
        User user = userMapper.selectOne(
                Wrappers.<User>lambdaQuery().eq(User::getUsername, username));
        if (user == null || !passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
            throw BizException.of(ResultCode.BAD_REQUEST, "用户名或密码错误");
        }
        if (user.getStatus() == null || user.getStatus() != 1) {
            throw BizException.of(ResultCode.FORBIDDEN, "账号已被禁用");
        }
        return user;
    }

    /**
     * 注销：逻辑删除并将用户名改写为 {@code name#id}，释放唯一键。
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
