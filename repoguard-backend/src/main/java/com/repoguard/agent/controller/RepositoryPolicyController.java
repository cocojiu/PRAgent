package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.config.ApiRuntimeEnabled;
import com.repoguard.agent.dto.RepositoryPolicyPreviewResponse;
import com.repoguard.agent.dto.RepositorySuppressionRequest;
import com.repoguard.agent.dto.RepositorySuppressionResponse;
import com.repoguard.agent.review.RepositoryPolicyEvaluationService;
import com.repoguard.agent.review.RepositoryPolicyRuntime;
import com.repoguard.agent.review.RepositorySuppressionService;
import com.repoguard.agent.security.RequireRole;
import com.repoguard.agent.web.RequestAuthentication;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/config/repository-policy")
@ApiRuntimeEnabled
@RequireRole({"ADMIN", "PLATFORM_ADMIN", "RULE_ADMIN"})
public class RepositoryPolicyController {

    private final RepositoryPolicyRuntime policyRuntime;
    private final RepositorySuppressionService suppressionService;

    public RepositoryPolicyController(
        RepositoryPolicyRuntime policyRuntime,
        RepositorySuppressionService suppressionService
    ) {
        this.policyRuntime = policyRuntime;
        this.suppressionService = suppressionService;
    }

    @GetMapping("/preview")
    public ApiResponse<RepositoryPolicyPreviewResponse> preview(
        @RequestParam @NotBlank @Size(max = 128) String organization,
        @RequestParam @NotBlank @Size(max = 255) String repository,
        @RequestParam(required = false) @Size(max = 128) String headSha
    ) {
        RepositoryPolicyEvaluationService.RepositoryPolicyEvaluation evaluation = policyRuntime.preview(
            organization,
            repository,
            headSha
        );
        return ApiResponse.ok(new RepositoryPolicyPreviewResponse(
            evaluation.basePolicy(),
            evaluation.headPolicy(),
            evaluation.rules(),
            evaluation.effectiveSettings().llmEnabled(),
            evaluation.effectiveSettings().maxTokens(),
            evaluation.costBudget(),
            evaluation.commentMode(),
            evaluation.checkMode(),
            evaluation.warnings()
        ));
    }

    @PostMapping("/suppressions")
    public ApiResponse<RepositorySuppressionResponse> createSuppression(
        HttpServletRequest request,
        @Valid @RequestBody RepositorySuppressionRequest payload
    ) {
        return ApiResponse.ok(suppressionService.create(payload, username(request)));
    }

    @GetMapping("/suppressions")
    public ApiResponse<List<RepositorySuppressionResponse>> listSuppressions(
        @RequestParam @NotBlank @Size(max = 128) String organization,
        @RequestParam @NotBlank @Size(max = 255) String repository,
        @RequestParam(defaultValue = "50") @Min(1) @Max(100) int limit
    ) {
        return ApiResponse.ok(suppressionService.list(organization, repository, limit));
    }

    @PostMapping("/suppressions/{id}/activate")
    public ApiResponse<RepositorySuppressionResponse> activate(
        HttpServletRequest request,
        @PathVariable @Min(1) long id,
        @RequestParam(required = false) @Size(max = 512) String reason
    ) {
        return ApiResponse.ok(suppressionService.activate(id, username(request), reason));
    }

    @PostMapping("/suppressions/{id}/revoke")
    public ApiResponse<RepositorySuppressionResponse> revoke(
        HttpServletRequest request,
        @PathVariable @Min(1) long id,
        @RequestParam(required = false) @Size(max = 512) String reason
    ) {
        return ApiResponse.ok(suppressionService.revoke(id, username(request), reason));
    }

    private String username(HttpServletRequest request) {
        return RequestAuthentication.require(request).username();
    }
}
