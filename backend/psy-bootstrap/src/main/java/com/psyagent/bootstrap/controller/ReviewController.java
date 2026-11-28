package com.psyagent.bootstrap.controller;

import com.psyagent.bootstrap.security.AuthUser;
import com.psyagent.bootstrap.security.CurrentUser;
import com.psyagent.bootstrap.service.ReviewTaskService;
import com.psyagent.common.api.R;
import com.psyagent.common.api.ResultCode;
import com.psyagent.common.exception.BizException;
import com.psyagent.store.entity.ReviewTask;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 人工复核队列。
 * <p>
 * 这是**工作人员**的接口，不是用户接口：待办列表里是别人的倾诉片段，
 * 任何登录用户都能看到就是一次数据泄露。
 * <p>
 * 当前项目只有"登录 / 未登录"一层身份，没有角色表，因此用配置白名单
 * （{@code psy.review.admin-usernames}）做最小可行的隔离。默认**拒绝**：
 * 白名单为空时谁都进不来，而不是"谁都进得来"——安全配置的默认值必须是关闭。
 * <p>
 * 真要做成多角色系统，应把 role 落库、写进令牌、在网关层统一校验，
 * 而不是在每个 Controller 里判断用户名。
 */
@RestController
@RequestMapping("/api/review")
public class ReviewController {

    private final ReviewTaskService reviewTaskService;
    private final Set<String> adminUsernames;

    public ReviewController(ReviewTaskService reviewTaskService,
                            @Value("${psy.review.admin-usernames:}") String adminUsernames) {
        this.reviewTaskService = reviewTaskService;
        this.adminUsernames = Arrays.stream(adminUsernames.split(","))
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
    }

    /** 待办列表：按首次响应时限升序，最该处理的排在最前。 */
    @GetMapping("/tasks")
    public R<List<ReviewTask>> tasks(@RequestParam(required = false) String status,
                                     @RequestParam(defaultValue = "20") int limit) {
        requireStaff();
        return R.ok(reviewTaskService.list(status, limit));
    }

    /** 队列概况：各状态数量、逾期数、平均首次响应耗时。 */
    @GetMapping("/summary")
    public R<Map<String, Object>> summary(@RequestParam(defaultValue = "24") int hours) {
        requireStaff();
        return R.ok(reviewTaskService.summary(Math.max(1, Math.min(720, hours))));
    }

    @PostMapping("/tasks/{id}/claim")
    public R<ReviewTask> claim(@PathVariable Long id) {
        AuthUser user = requireStaff();
        return R.ok(reviewTaskService.claim(id, user.username()));
    }

    @PostMapping("/tasks/{id}/close")
    public R<ReviewTask> close(@PathVariable Long id, @RequestBody CloseRequest request) {
        requireStaff();
        return R.ok(reviewTaskService.close(id, request.status(), request.conclusion()));
    }

    private AuthUser requireStaff() {
        AuthUser user = CurrentUser.get();
        if (adminUsernames.isEmpty() || !adminUsernames.contains(user.username())) {
            throw BizException.of(ResultCode.FORBIDDEN, "需要复核权限");
        }
        return user;
    }

    public record CloseRequest(String status, String conclusion) {
    }
}
