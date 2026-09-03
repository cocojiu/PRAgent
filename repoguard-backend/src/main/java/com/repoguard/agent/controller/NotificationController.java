package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.config.ApiRuntimeEnabled;
import com.repoguard.agent.config.EnterpriseEditionEnabled;
import com.repoguard.agent.dto.NotificationCenterDto;
import com.repoguard.agent.dto.NotificationReadRequest;
import com.repoguard.agent.dto.NotificationReportDto;
import com.repoguard.agent.security.RequireRole;
import com.repoguard.agent.service.ReviewWorkflowService;
import com.repoguard.agent.web.RequestAuthentication;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import com.repoguard.agent.service.NotificationService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;

@RestController
@RequestMapping("/api/v1/notifications")
@ApiRuntimeEnabled
@EnterpriseEditionEnabled
public class NotificationController {

    private final NotificationService notificationService;
    private final ReviewWorkflowService workflowService;

    @Autowired
    public NotificationController(NotificationService notificationService, ReviewWorkflowService workflowService) {
        this.notificationService = notificationService;
        this.workflowService = workflowService;
    }

    public NotificationController(NotificationService notificationService) {
        this(notificationService, null);
    }

    @GetMapping
    public ApiResponse<NotificationCenterDto> getNotifications() {
        return ApiResponse.ok(notificationService.getNotifications());
    }

    @PostMapping("/read")
    @RequireRole({"ADMIN", "TENANT_ADMIN", "REVIEWER", "READ_ONLY"})
    public ApiResponse<Void> markRead(HttpServletRequest request, @Valid @RequestBody NotificationReadRequest read) {
        workflowService.markNotificationRead(read, RequestAuthentication.require(request).username());
        return ApiResponse.ok(null);
    }

    @GetMapping("/read")
    @RequireRole({"ADMIN", "TENANT_ADMIN", "REVIEWER", "READ_ONLY"})
    public ApiResponse<List<String>> readKeys(HttpServletRequest request) {
        return ApiResponse.ok(workflowService.listReadNotificationKeys(RequestAuthentication.require(request).username()));
    }

    @GetMapping("/reports")
    @RequireRole({"ADMIN", "TENANT_ADMIN", "REVIEWER", "READ_ONLY"})
    public ApiResponse<NotificationReportDto> report(
        @RequestParam(defaultValue = "DAILY") @Size(max = 16) String period
    ) {
        return ApiResponse.ok(workflowService.report(period));
    }
}
