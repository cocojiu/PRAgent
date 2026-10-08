package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.ApiRuntimeEnabled;
import com.repoguard.agent.config.EnterpriseEditionEnabled;
import com.repoguard.agent.dto.CodeownersRecommendationResponse;
import com.repoguard.agent.dto.CodeownersRecommendationResponse.CodeownersCandidate;
import com.repoguard.agent.dto.CodeownersRecommendationResponse.CodeownersPath;
import com.repoguard.agent.notification.workflow.CodeownersQueryService;
import com.repoguard.agent.security.RequireRole;
import com.repoguard.agent.tenancy.TenantContext;
import com.repoguard.agent.dto.CodeownersAcceptRequest;
import com.repoguard.agent.dto.CodeownersAcceptResponse;
import com.repoguard.agent.service.ReviewWorkflowService;
import com.repoguard.agent.web.RequestAuthentication;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Validated
@ApiRuntimeEnabled
@EnterpriseEditionEnabled
@RequireRole({"ADMIN", "TENANT_ADMIN", "REVIEWER"})
public class CodeownersRecommendationController {
    private final CodeownersQueryService query;
    private final ReviewWorkflowService workflow;
    public CodeownersRecommendationController(CodeownersQueryService query, ReviewWorkflowService workflow) {
        this.query = query; this.workflow = workflow;
    }

    @GetMapping("/api/v1/review-workflow/tasks/{taskId}/codeowners")
    public ApiResponse<CodeownersRecommendationResponse> recommendations(@PathVariable @Min(1) Long taskId,
                                                                         HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        if (!TenantContext.hasTenant()) throw new BusinessException(ErrorCode.FORBIDDEN, "Tenant context required");
        var result = query.query(taskId);
        if ("NOT_FOUND".equals(result.status())) throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found");
        var recommendation = result.recommendations();
        return ApiResponse.ok(new CodeownersRecommendationResponse(result.status(), result.taskId(), result.attemptId(),
            result.headSha(), result.baseSha(), result.sourcePath(), recommendation == null ? List.of() :
            recommendation.candidates().stream().map(candidate -> new CodeownersCandidate(candidate.userId(), candidate.username(),
                candidate.coveredFiles(), candidate.findingCount(), candidate.riskScore(), candidate.externalIdentities(), candidate.paths())).toList(),
            recommendation == null ? List.of() : recommendation.basis().stream().map(path ->
                new CodeownersPath(path.path(), path.pattern(), path.line(), path.owners(), path.changedFiles())).toList(),
            recommendation == null ? List.of() : recommendation.uncoveredPaths(),
            recommendation == null ? List.of() : recommendation.unmappedIdentities()));
    }

    @PostMapping("/api/v1/review-workflow/tasks/{taskId}/codeowners/accept")
    public ApiResponse<CodeownersAcceptResponse> accept(HttpServletRequest request, HttpServletResponse response,
        @PathVariable @Min(1) Long taskId, @Valid @RequestBody CodeownersAcceptRequest selection) {
        response.setHeader("Cache-Control", "no-store");
        if (!TenantContext.hasTenant()) throw new BusinessException(ErrorCode.FORBIDDEN, "Tenant context required");
        var actor = RequestAuthentication.require(request);
        var current = query.query(taskId);
        if ("NOT_FOUND".equals(current.status())) throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found");
        if (!"RECOMMENDATIONS".equals(current.status()) || current.assignment() == null
            || !selection.attemptId().equals(current.attemptId())
            || !selection.headSha().equalsIgnoreCase(current.headSha()) || !selection.baseSha().equalsIgnoreCase(current.baseSha()))
            throw new BusinessException(ErrorCode.CONFLICT, "Recommendation changed or unavailable, please refresh");
        var candidate = current.recommendations().candidates().stream().filter(value -> value.userId().equals(selection.userId()))
            .findFirst().orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "Selected member is no longer recommended"));
        var assigned = workflow.assignRecommended(taskId, candidate.username(), current.assignment(), actor.username());
        return ApiResponse.ok(new CodeownersAcceptResponse(taskId, current.attemptId(), current.headSha(), assigned.assignee()));
    }
}
