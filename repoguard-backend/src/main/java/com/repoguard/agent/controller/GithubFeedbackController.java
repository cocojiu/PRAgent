package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.ApiRuntimeEnabled;
import com.repoguard.agent.github.webhook.GithubFeedbackEventStore;
import com.repoguard.agent.github.webhook.GithubFeedbackService;
import com.repoguard.agent.security.RequireRole;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@ApiRuntimeEnabled
@RequireRole("ADMIN")
@RequestMapping("/api/v1/github/feedback-events")
public class GithubFeedbackController {
    private final GithubFeedbackEventStore store;
    private final GithubFeedbackService feedback;

    public GithubFeedbackController(GithubFeedbackEventStore store, GithubFeedbackService feedback) {
        this.store = store;
        this.feedback = feedback;
    }

    @GetMapping
    public ApiResponse<GithubFeedbackDiagnostics> diagnostics(@RequestParam(defaultValue = "20") int limit) {
        if (limit < 1 || limit > 100) throw new BusinessException(ErrorCode.BAD_REQUEST, "limit 必须介于 1 和 100");
        return ApiResponse.ok(new GithubFeedbackDiagnostics(feedback.enabled(), store.list(limit)));
    }

    @PostMapping("/{id}/retry")
    public ApiResponse<Void> retry(@PathVariable long id) {
        if (id < 1 || !feedback.enabled() || !store.retry(id)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "仅可在反馈已启用时重试当前租户的失败事件");
        }
        return ApiResponse.ok(null);
    }

    public record GithubFeedbackDiagnostics(boolean enabled, List<GithubFeedbackEventStore.EventSummary> events) { }
}
