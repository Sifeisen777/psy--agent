package com.psyagent.bootstrap.controller;

import com.psyagent.bootstrap.service.AuthService;
import com.psyagent.common.api.R;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 账号接口。
 * <p>
 * 当前版本尚未接入身份认证，注册后返回 userId 由调用方持有。
 * 接入令牌校验后，用户身份应从中解析，这两个接口的契约将随之调整。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public R<Map<String, Object>> register(@RequestBody @Valid RegisterRequest request) {
        Long userId = authService.register(request.username(), request.password());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("userId", userId);
        body.put("username", request.username());
        return R.ok(body);
    }

    /**
     * 注销（方案 A）：逻辑删除 + 用户名改写为 {@code name#id}，同名可重新注册。
     */
    @PostMapping("/unregister")
    public R<Void> unregister(@RequestParam Long userId) {
        authService.unregister(userId);
        return R.ok();
    }

    public record RegisterRequest(
            @NotBlank(message = "用户名不能为空")
            @Size(min = 3, max = 64, message = "用户名长度 3-64") String username,

            @NotBlank(message = "密码不能为空")
            @Size(min = 6, max = 64, message = "密码长度 6-64") String password
    ) {
    }
}
