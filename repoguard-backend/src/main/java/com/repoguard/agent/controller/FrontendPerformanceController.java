package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.config.ApiRuntimeEnabled;
import com.repoguard.agent.dto.FrontendPerformanceReportRequest;
import com.repoguard.agent.security.RequireRole;
import com.repoguard.agent.service.FrontendPerformanceObservationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/observability/frontend")
@ApiRuntimeEnabled
public class FrontendPerformanceController {

    private final FrontendPerformanceObservationService observationService;

    public FrontendPerformanceController(FrontendPerformanceObservationService observationService) {
        this.observationService = observationService;
    }

    @RequireRole({"ADMIN", "VIEWER"})
    @PostMapping("/performance")
    public ApiResponse<Void> recordPerformance(@Valid @RequestBody FrontendPerformanceReportRequest request) {
        observationService.record(request);
        return ApiResponse.ok(null);
    }
}
