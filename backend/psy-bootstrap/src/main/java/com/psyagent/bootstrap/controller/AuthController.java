package com.psyagent.bootstrap.controller;

import com.psyagent.bootstrap.security.CurrentUser;
import com.psyagent.bootstrap.security.JwtService;
import com.psyagent.bootstrap.service.AuthService;
import com.psyagent.common.api.R;
import com.psyagent.store.entity.User;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 账号接口。
 * <p>
 * 注册与登录返回访问令牌，后续请求通过 {@code Authorization: Bearer <token>} 认证；
 * 注销操作作用于当前令牌对应的账号，不再接收用户标识参数。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final JwtService jwtService;

    public AuthController(AuthService authService, JwtService jwtService) {
        this.authService = authService;
        this.jwtService = jwtService;
    }

    /**
     * 注册并直接签发令牌，省去注册后的二次登录。
     */
    @PostMapping("/register")
    public R<Map<String, Object>> register(@RequestBody @Valid RegisterRequest request) {
        Long userId = authService.register(request.username(), request.password());
        return R.ok(tokenPayload(userId, request.username()));
    }

    @PostMapping("/login")
    public R<Map<String, Object>> login(@RequestBody @Valid LoginRequest request) {
        User user = authService.authenticate(request.username(), request.password());
        return R.ok(tokenPayload(user.getId(), user.getUsername()));
    }

    /**
     * 注销当前账号（逻辑删除并释放用户名）。
     */
    @PostMapping("/unregister")
    public R<Void> unregister() {
        authService.unregister(CurrentUser.id());
        return R.ok();
    }

    private Map<String, Object> tokenPayload(Long userId, String username) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", String.valueOf(userId));
        body.put("username", username);
        body.put("token", jwtService.issue(userId, username));
        body.put("expiresIn", (int) jwtService.expirationSeconds());
        return body;
    }

    public record RegisterRequest(
            @NotBlank(message = "用户名不能为空")
            @Size(min = 3, max = 64, message = "用户名长度 3-64") String username,

            @NotBlank(message = "密码不能为空")
            @Size(min = 6, max = 64, message = "密码长度 6-64") String password
    ) {
    }

    public record LoginRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空") String password
    ) {
    }
}
