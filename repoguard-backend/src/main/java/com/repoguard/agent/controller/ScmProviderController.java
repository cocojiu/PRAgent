package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.config.ApiRuntimeEnabled;
import com.repoguard.agent.review.PullRequestDiff;
import com.repoguard.agent.scm.ScmChangeRequestSummary;
import com.repoguard.agent.scm.ScmCommentDraft;
import com.repoguard.agent.scm.ScmCommentResult;
import com.repoguard.agent.scm.ScmProviderRegistry;
import com.repoguard.agent.scm.ScmStatusRequest;
import com.repoguard.agent.scm.ScmStatusResult;
import com.repoguard.agent.security.RequireRole;
import com.repoguard.agent.service.ScmProviderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import java.util.List;
import java.util.Map;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Provider-neutral SCM endpoints used by enterprise integrations and automation. */
@RestController
@RequestMapping("/api/v1/scm")
@ApiRuntimeEnabled
@RequireRole({"ADMIN", "TENANT_ADMIN", "REVIEWER"})
@Validated
public class ScmProviderController {

    private final ScmProviderService scmProviderService;

    public ScmProviderController(ScmProviderService scmProviderService) {
        this.scmProviderService = scmProviderService;
    }

    @GetMapping("/providers")
    public ApiResponse<List<ScmProviderRegistry.ScmProviderDescriptor>> providers() {
        return ApiResponse.ok(scmProviderService.providers());
    }

    @GetMapping("/providers/{provider}/change-requests")
    public ApiResponse<List<ScmChangeRequestSummary>> changeRequests(@PathVariable String provider) {
        return ApiResponse.ok(scmProviderService.changeRequests(provider));
    }

    @GetMapping("/providers/{provider}/tasks/{taskId}/diff")
    public ApiResponse<PullRequestDiff> diff(
        @PathVariable String provider,
        @PathVariable @Min(1) Long taskId
    ) {
        return ApiResponse.ok(scmProviderService.diff(provider, taskId));
    }

    @GetMapping("/providers/{provider}/tasks/{taskId}/head")
    public ApiResponse<Map<String, String>> head(
        @PathVariable String provider,
        @PathVariable @Min(1) Long taskId
    ) {
        return ApiResponse.ok(scmProviderService.head(provider, taskId));
    }

    @PostMapping("/providers/{provider}/tasks/{taskId}/comments")
    public ApiResponse<ScmCommentResult> comment(
        @PathVariable String provider,
        @PathVariable @Min(1) Long taskId,
        @Valid @RequestBody ScmCommentDraft draft
    ) {
        return ApiResponse.ok(scmProviderService.comment(provider, taskId, draft));
    }

    @PostMapping("/providers/{provider}/tasks/{taskId}/status")
    public ApiResponse<ScmStatusResult> status(
        @PathVariable String provider,
        @PathVariable @Min(1) Long taskId,
        @Valid @RequestBody ScmStatusRequest request
    ) {
        return ApiResponse.ok(scmProviderService.status(provider, taskId, request));
    }
}
