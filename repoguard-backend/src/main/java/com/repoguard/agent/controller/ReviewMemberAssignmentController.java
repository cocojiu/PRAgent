package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.config.ApiRuntimeEnabled;
import com.repoguard.agent.config.EnterpriseEditionEnabled;
import com.repoguard.agent.dto.ReviewAssignmentOptionsResponse;
import com.repoguard.agent.dto.ReviewMemberAssignmentRequest;
import com.repoguard.agent.dto.ReviewMemberAssignmentResponse;
import com.repoguard.agent.notification.workflow.ReviewMemberAssignmentService;
import com.repoguard.agent.security.RequireRole;
import com.repoguard.agent.web.RequestAuthentication;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@Validated
@ApiRuntimeEnabled
@EnterpriseEditionEnabled
@RequireRole({"ADMIN", "TENANT_ADMIN", "REVIEWER"})
public class ReviewMemberAssignmentController {
    private final ReviewMemberAssignmentService service;
    public ReviewMemberAssignmentController(ReviewMemberAssignmentService service) { this.service = service; }

    @GetMapping("/api/v1/review-workflow/tasks/{taskId}/assignment-options")
    public ApiResponse<ReviewAssignmentOptionsResponse> options(@PathVariable @Min(1) Long taskId,
        @RequestParam(required = false) @Size(max = 128) String search, HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.ok(service.options(taskId, search));
    }

    @PostMapping("/api/v1/review-workflow/tasks/{taskId}/assignment/confirm")
    public ApiResponse<ReviewMemberAssignmentResponse> confirm(HttpServletRequest request, HttpServletResponse response,
        @PathVariable @Min(1) Long taskId, @Valid @RequestBody ReviewMemberAssignmentRequest selection) {
        response.setHeader("Cache-Control", "no-store");
        return ApiResponse.ok(service.confirm(taskId, selection, RequestAuthentication.require(request).username()));
    }
}
