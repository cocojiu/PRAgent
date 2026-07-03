package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.ConnectionTestResultDto;
import com.repoguard.agent.dto.NotificationBindingDto;
import com.repoguard.agent.dto.NotificationBindingRequest;
import com.repoguard.agent.dto.NotificationBindingStatusRequest;
import com.repoguard.agent.dto.NotificationDeliveryDto;
import com.repoguard.agent.dto.NotificationEventDto;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.security.RequireRole;
import com.repoguard.agent.service.NotificationIntegrationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
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
public class NotificationIntegrationController {

    private final NotificationIntegrationService service;

    public NotificationIntegrationController(NotificationIntegrationService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/config/notification-bindings")
    public ApiResponse<PageResponse<NotificationBindingDto>> listBindings(
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
        @RequestParam(required = false) @Size(max = 128) String organization,
        @RequestParam(required = false) @Size(max = 128) String repository,
        @RequestParam(required = false) @Size(max = 32) String provider
    ) {
        return ApiResponse.ok(service.listBindings(
            page,
            pageSize,
            checkedParam("organization", organization, 128),
            checkedParam("repository", repository, 128),
            checkedParam("provider", provider, 32)
        ));
    }

    @PostMapping("/api/v1/config/notification-bindings")
    @RequireRole("ADMIN")
    public ApiResponse<NotificationBindingDto> createBinding(@Valid @RequestBody NotificationBindingRequest request) {
        return ApiResponse.ok(service.createBinding(request));
    }

    @PutMapping("/api/v1/config/notification-bindings/{id}")
    @RequireRole("ADMIN")
    public ApiResponse<NotificationBindingDto> updateBinding(
        @PathVariable @Min(1) Long id,
        @Valid @RequestBody NotificationBindingRequest request
    ) {
        return ApiResponse.ok(service.updateBinding(id, request));
    }

    @PutMapping("/api/v1/config/notification-bindings/{id}/status")
    @RequireRole("ADMIN")
    public ApiResponse<NotificationBindingDto> updateBindingStatus(
        @PathVariable @Min(1) Long id,
        @Valid @RequestBody NotificationBindingStatusRequest request
    ) {
        return ApiResponse.ok(service.updateBindingStatus(id, request.enabled()));
    }

    @DeleteMapping("/api/v1/config/notification-bindings/{id}")
    @RequireRole("ADMIN")
    public ApiResponse<Void> deleteBinding(@PathVariable @Min(1) Long id) {
        service.deleteBinding(id);
        return ApiResponse.ok(null);
    }

    @PostMapping("/api/v1/config/notification-bindings/{id}/test")
    @RequireRole("ADMIN")
    public ApiResponse<ConnectionTestResultDto> testBinding(@PathVariable @Min(1) Long id) {
        return ApiResponse.ok(service.testBinding(id));
    }

    @GetMapping("/api/v1/notification-events")
    public ApiResponse<PageResponse<NotificationEventDto>> listEvents(
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
        @RequestParam(required = false) @Size(max = 32) String status,
        @RequestParam(required = false) Long taskId
    ) {
        return ApiResponse.ok(service.listEvents(page, pageSize, checkedParam("status", status, 32), taskId));
    }

    @PostMapping("/api/v1/notification-events/{id}/retry")
    @RequireRole("ADMIN")
    public ApiResponse<NotificationEventDto> retryEvent(@PathVariable @Min(1) Long id) {
        return ApiResponse.ok(service.retryEvent(id));
    }

    @GetMapping("/api/v1/notification-deliveries")
    public ApiResponse<PageResponse<NotificationDeliveryDto>> listDeliveries(
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize,
        @RequestParam(required = false) @Size(max = 32) String status,
        @RequestParam(required = false) Long taskId
    ) {
        return ApiResponse.ok(service.listDeliveries(page, pageSize, checkedParam("status", status, 32), taskId));
    }

    private String checkedParam(String name, String value, int maxLength) {
        if (value != null && value.length() > maxLength) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, name + " must be at most " + maxLength + " characters");
        }
        return value;
    }
}
