package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.config.ApiRuntimeEnabled;
import com.repoguard.agent.dto.LlmModelReleaseCenterDto;
import com.repoguard.agent.dto.LlmModelReleaseDto;
import com.repoguard.agent.dto.LlmModelReleaseDto.LlmModelReleaseAuditDto;
import com.repoguard.agent.dto.LlmModelReleaseDto.LlmModelReleaseAuditExportDto;
import com.repoguard.agent.dto.LlmModelReleaseDto.LlmModelReleaseAuditVerificationDto;
import com.repoguard.agent.dto.LlmModelReleaseRequest;
import com.repoguard.agent.dto.LlmModelReleaseRequest.LlmEvaluationReportLifecycleRequest;
import com.repoguard.agent.dto.LlmModelReleaseRequest.LlmEvaluationRequest;
import com.repoguard.agent.dto.LlmModelRollbackRequest;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.dto.ReviewCalibrationQueueDto;
import com.repoguard.agent.dto.LlmModelReleaseMetricDto;
import com.repoguard.agent.review.quality.LlmModelReleaseService;
import com.repoguard.agent.review.quality.LlmModelReleaseMetricsService;
import com.repoguard.agent.security.RequireRole;
import com.repoguard.agent.service.ReviewCalibrationService;
import com.repoguard.agent.web.RequestAuthentication;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/config/review-calibration")
@ApiRuntimeEnabled
@RequireRole({"ADMIN", "PLATFORM_ADMIN", "RULE_ADMIN"})
@Validated
public class ReviewCalibrationController {

    private final ReviewCalibrationService calibrationService;
    private final LlmModelReleaseService modelReleaseService;
    private final LlmModelReleaseMetricsService modelReleaseMetricsService;

    public ReviewCalibrationController(ReviewCalibrationService calibrationService) {
        this(calibrationService, null, null);
    }

    public ReviewCalibrationController(
        ReviewCalibrationService calibrationService,
        LlmModelReleaseService modelReleaseService
    ) {
        this(calibrationService, modelReleaseService, null);
    }

    @Autowired
    public ReviewCalibrationController(
        ReviewCalibrationService calibrationService,
        LlmModelReleaseService modelReleaseService,
        LlmModelReleaseMetricsService modelReleaseMetricsService
    ) {
        this.calibrationService = calibrationService;
        this.modelReleaseService = modelReleaseService;
        this.modelReleaseMetricsService = modelReleaseMetricsService;
    }

    @GetMapping("/queue")
    public ApiResponse<ReviewCalibrationQueueDto> getReviewCalibrationQueue(
        @RequestParam @Size(max = 64) String ruleId,
        @RequestParam(defaultValue = "30") @Min(1) @Max(100) int limit,
        @RequestParam(defaultValue = "false") boolean includeIgnored
    ) {
        return ApiResponse.ok(calibrationService.getQueue(ruleId, limit, includeIgnored));
    }

    @GetMapping("/release-center")
    public ApiResponse<LlmModelReleaseCenterDto> getModelReleaseCenter(
        @RequestParam(defaultValue = "30") @Min(7) @Max(90) int trendDays
    ) {
        return ApiResponse.ok(requireReleaseService().getCenter(trendDays));
    }

    @GetMapping("/release-center/runtime-metrics")
    public ApiResponse<java.util.List<LlmModelReleaseMetricDto>> listModelReleaseRuntimeMetrics(
        @RequestParam(required = false) @Size(max = 128) String releaseKey,
        @RequestParam(defaultValue = "7") @Min(1) @Max(90) int days,
        @RequestParam(defaultValue = "168") @Min(1) @Max(500) int limit
    ) {
        return ApiResponse.ok(requireMetricsService().collectAndList(releaseKey, days, limit));
    }

    @GetMapping("/release-center/audits")
    public ApiResponse<PageResponse<LlmModelReleaseAuditDto>> listModelReleaseAudits(
        @RequestParam(required = false) @Min(1) Long releaseId,
        @RequestParam(required = false) @Size(max = 128) String releaseKey,
        @RequestParam(required = false) @Size(max = 128) String operator,
        @RequestParam(required = false) @Size(max = 32) String action,
        @RequestParam(required = false) @Size(max = 40) String from,
        @RequestParam(required = false) @Size(max = 40) String to,
        @RequestParam(defaultValue = "1") @Min(1) @Max(10_000) int page,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return ApiResponse.ok(requireReleaseService().listReleaseAudits(
            releaseId, releaseKey, operator, action, from, to, page, pageSize));
    }

    @GetMapping("/release-center/audits/{auditId}/verify")
    public ApiResponse<LlmModelReleaseAuditVerificationDto> verifyModelReleaseAudit(
        @PathVariable @Min(1) long auditId
    ) {
        return ApiResponse.ok(requireReleaseService().verifyReleaseAudit(auditId));
    }

    @GetMapping("/release-center/audits/export")
    public ApiResponse<LlmModelReleaseAuditExportDto> exportModelReleaseAudits(
        @RequestParam(required = false) @Min(1) Long releaseId,
        @RequestParam(required = false) @Size(max = 128) String releaseKey,
        @RequestParam(required = false) @Size(max = 128) String operator,
        @RequestParam(required = false) @Size(max = 32) String action,
        @RequestParam(required = false) @Size(max = 40) String from,
        @RequestParam(required = false) @Size(max = 40) String to,
        @RequestParam(defaultValue = "json") @Size(max = 8) String format
    ) {
        return ApiResponse.ok(requireReleaseService().exportReleaseAudits(
            releaseId, releaseKey, operator, action, from, to, format));
    }

    @PostMapping("/release-center/shadow")
    public ApiResponse<LlmModelReleaseDto> registerShadowRelease(
        @Valid @RequestBody LlmModelReleaseRequest request,
        HttpServletRequest servletRequest
    ) {
        return ApiResponse.ok(requireReleaseService().registerShadow(
            request,
            RequestAuthentication.require(servletRequest).username()
        ));
    }

    @PostMapping("/release-center/promote")
    public ApiResponse<LlmModelReleaseDto> promoteModelRelease(
        @Valid @RequestBody LlmModelReleaseRequest request,
        HttpServletRequest servletRequest
    ) {
        return ApiResponse.ok(requireReleaseService().promote(
            request,
            RequestAuthentication.require(servletRequest).username()
        ));
    }

    @PostMapping("/evaluation-reports")
    public ApiResponse<LlmModelReleaseDto.EvaluationReportDto> createEvaluationReport(
        @Valid @RequestBody LlmEvaluationRequest request,
        HttpServletRequest servletRequest
    ) {
        return ApiResponse.ok(requireReleaseService().createEvaluationReport(request,
            RequestAuthentication.require(servletRequest).username()));
    }

    @GetMapping("/evaluation-reports")
    public ApiResponse<java.util.List<LlmModelReleaseDto.EvaluationReportDto>> listEvaluationReports(
        @RequestParam(defaultValue = "30") @Min(1) @Max(100) int limit
    ) {
        return ApiResponse.ok(requireReleaseService().listEvaluationReports(limit));
    }

    @GetMapping("/evaluation-reports/{reportId}")
    public ApiResponse<LlmModelReleaseDto.EvaluationReportDto> getEvaluationReport(
        @PathVariable @Min(1) long reportId
    ) {
        return ApiResponse.ok(requireReleaseService().getEvaluationReport(reportId));
    }

    @GetMapping("/evaluation-reports/{reportId}/compare/{candidateReportId}")
    public ApiResponse<LlmModelReleaseDto.EvaluationReportComparisonDto> compareEvaluationReports(
        @PathVariable @Min(1) long reportId,
        @PathVariable @Min(1) long candidateReportId
    ) {
        return ApiResponse.ok(requireReleaseService().compareEvaluationReports(reportId, candidateReportId));
    }

    @GetMapping("/evaluation-reports/{reportId}/export")
    @RequireRole({"ADMIN", "PLATFORM_ADMIN"})
    public ApiResponse<LlmModelReleaseDto.EvaluationExportDto> exportEvaluationReport(
        @PathVariable @Min(1) long reportId,
        @RequestParam(defaultValue = "json") @Size(max = 8) String format,
        HttpServletRequest servletRequest
    ) {
        var principal = RequestAuthentication.require(servletRequest);
        return ApiResponse.ok(requireReleaseService().exportEvaluationReport(
            reportId, format, principal.username(), principal.role()));
    }

    @PostMapping("/evaluation-reports/{reportId}/lifecycle")
    public ApiResponse<LlmModelReleaseDto.EvaluationReportDto> transitionEvaluationReportLifecycle(
        @PathVariable @Min(1) long reportId,
        @Valid @RequestBody LlmEvaluationReportLifecycleRequest request,
        HttpServletRequest servletRequest
    ) {
        var principal = RequestAuthentication.require(servletRequest);
        return ApiResponse.ok(requireReleaseService().transitionEvaluationReport(
            reportId, request, principal.username(), principal.role()));
    }

    @PostMapping("/release-center/{releaseId}/rollback")
    public ApiResponse<LlmModelReleaseDto> rollbackModelRelease(
        @PathVariable @Min(1) long releaseId,
        @Valid @RequestBody LlmModelRollbackRequest request,
        HttpServletRequest servletRequest
    ) {
        return ApiResponse.ok(requireReleaseService().rollback(
            releaseId,
            request,
            RequestAuthentication.require(servletRequest).username()
        ));
    }

    private LlmModelReleaseService requireReleaseService() {
        if (modelReleaseService == null) {
            throw new IllegalStateException("LLM model release center is not available");
        }
        return modelReleaseService;
    }

    private LlmModelReleaseMetricsService requireMetricsService() {
        if (modelReleaseMetricsService == null) {
            throw new IllegalStateException("LLM model release runtime metrics are not available");
        }
        return modelReleaseMetricsService;
    }
}
