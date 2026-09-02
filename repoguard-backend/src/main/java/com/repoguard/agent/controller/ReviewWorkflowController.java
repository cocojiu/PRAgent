package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.config.ApiRuntimeEnabled;
import com.repoguard.agent.config.EnterpriseEditionEnabled;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.dto.ReviewAssignmentRequest;
import com.repoguard.agent.dto.ReviewBotCommandRequest;
import com.repoguard.agent.dto.ReviewBotCommandResponse;
import com.repoguard.agent.dto.ReviewEscalationResponse;
import com.repoguard.agent.dto.ReviewWorkflowItemDto;
import com.repoguard.agent.security.RequireRole;
import com.repoguard.agent.service.ReviewWorkflowService;
import com.repoguard.agent.web.RequestAuthentication;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@Validated
@RestController
@RequestMapping("/api/v1/review-workflow")
@ApiRuntimeEnabled
@EnterpriseEditionEnabled
public class ReviewWorkflowController {

    private final ReviewWorkflowService service;

    public ReviewWorkflowController(ReviewWorkflowService service) {
        this.service = service;
    }

    @GetMapping("/queue")
    @RequireRole({"ADMIN", "TENANT_ADMIN", "REVIEWER", "READ_ONLY"})
    public ApiResponse<PageResponse<ReviewWorkflowItemDto>> queue(
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
        @RequestParam(required = false) @Size(max = 128) String assignee,
        @RequestParam(required = false) Boolean overdue
    ) {
        return ApiResponse.ok(service.listQueue(page, pageSize, assignee, overdue));
    }

    @PutMapping("/tasks/{taskId}/assignment")
    @RequireRole({"ADMIN", "TENANT_ADMIN", "REVIEWER"})
    public ApiResponse<ReviewWorkflowItemDto> assign(
        HttpServletRequest request,
        @PathVariable @Min(1) Long taskId,
        @Valid @RequestBody ReviewAssignmentRequest assignment
    ) {
        return ApiResponse.ok(service.assign(taskId, assignment, RequestAuthentication.require(request).username()));
    }

    @PostMapping("/escalations")
    @RequireRole({"ADMIN", "TENANT_ADMIN"})
    public ApiResponse<ReviewEscalationResponse> escalate() {
        return ApiResponse.ok(service.escalateOverdue());
    }

    @PostMapping("/bot/{provider}/commands")
    @RequireRole({"ADMIN", "TENANT_ADMIN", "REVIEWER"})
    public ApiResponse<ReviewBotCommandResponse> command(
        HttpServletRequest request,
        @PathVariable @Size(max = 32) String provider,
        @Valid @RequestBody ReviewBotCommandRequest command
    ) {
        return ApiResponse.ok(service.executeBotCommand(
            provider,
            command,
            RequestAuthentication.require(request).username()
        ));
    }
}
