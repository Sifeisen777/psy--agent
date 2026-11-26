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
 * 账号接口（阶段 3 临时版）。
 * <p>
 * 目前没有登录态：注册返回 userId 后由前端自行携带。
 * 阶段 4 接入 JWT 后，userId 必须从 token 取，这两个接口的契约会变——
 * 届时用 `/api/auth/login` 换 token，`unregister` 从 token 里读用户，不再收参数。
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
