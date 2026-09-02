package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.config.ApiRuntimeEnabled;
import com.repoguard.agent.dto.ConnectionTestResultDto;
import com.repoguard.agent.dto.GithubIntegrationConfigDto;
import com.repoguard.agent.dto.GithubIntegrationConfigRequest;
import com.repoguard.agent.dto.GithubChecksPolicyRequest;
import com.repoguard.agent.dto.GithubChecksPreviewRequest;
import com.repoguard.agent.dto.GithubChecksSetupStatusDto;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.dto.ReviewPolicyConfigDto;
import com.repoguard.agent.dto.ReviewPolicyConfigRequest;
import com.repoguard.agent.dto.ReviewRuleConfigDto;
import com.repoguard.agent.dto.ReviewRuleConfigRequest;
import com.repoguard.agent.dto.ReviewRulePolicyVersionDto;
import com.repoguard.agent.dto.ReviewRuleRollbackRequest;
import com.repoguard.agent.dto.ReviewRuleStatusRequest;
import com.repoguard.agent.dto.ReviewRulesResponse;
import com.repoguard.agent.dto.ReviewStrategyPolicyDto;
import com.repoguard.agent.dto.ReviewStrategyRollbackRequest;
import com.repoguard.agent.dto.ReviewEnforcementModeRequest;
import com.repoguard.agent.dto.SecretReEncryptionItemDto;
import com.repoguard.agent.dto.SecretReEncryptionJobDto;
import com.repoguard.agent.dto.SecretReEncryptionRequest;
import com.repoguard.agent.dto.ServiceIntegrationConfigDto;
import com.repoguard.agent.dto.ServiceIntegrationConfigRequest;
import com.repoguard.agent.dto.SystemSettingsDto;
import com.repoguard.agent.dto.SystemSettingsRequest;
import com.repoguard.agent.security.RequireRole;
import com.repoguard.agent.security.SecretReEncryptionJobService;
import com.repoguard.agent.service.SystemConfigService;
import com.repoguard.agent.web.RequestAuthentication;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.beans.factory.annotation.Autowired;

@RestController
@RequestMapping("/api/v1/config")
@ApiRuntimeEnabled
@RequireRole("ADMIN")
public class SystemConfigController {

    private final SystemConfigService systemConfigService;
    private final SecretReEncryptionJobService secretReEncryptionJobService;
    private final com.repoguard.agent.github.checks.GithubChecksSetupService githubChecksSetupService;

    @Autowired
    public SystemConfigController(
        SystemConfigService systemConfigService,
        SecretReEncryptionJobService secretReEncryptionJobService,
        com.repoguard.agent.github.checks.GithubChecksSetupService githubChecksSetupService
    ) {
        this.systemConfigService = systemConfigService;
        this.secretReEncryptionJobService = secretReEncryptionJobService;
        this.githubChecksSetupService = githubChecksSetupService;
    }

    public SystemConfigController(
        SystemConfigService systemConfigService,
        SecretReEncryptionJobService secretReEncryptionJobService
    ) {
        this(systemConfigService, secretReEncryptionJobService, null);
    }

    @GetMapping("/integrations/github")
    public ApiResponse<GithubIntegrationConfigDto> getGithubIntegration() {
        return ApiResponse.ok(systemConfigService.getGithubIntegration());
    }

    @PutMapping("/integrations/github")
    public ApiResponse<GithubIntegrationConfigDto> updateGithubIntegration(
        @Valid @RequestBody GithubIntegrationConfigRequest request
    ) {
        return ApiResponse.ok(systemConfigService.updateGithubIntegration(request));
    }

    @PostMapping("/integrations/github/test")
    public ApiResponse<ConnectionTestResultDto> testGithubIntegration(
        @Valid @RequestBody(required = false) GithubIntegrationConfigRequest request
    ) {
        return ApiResponse.ok(systemConfigService.testGithubIntegration(request));
    }

    @GetMapping("/integrations/github/checks")
    public ApiResponse<GithubChecksSetupStatusDto> getGithubChecksSetup(
        @RequestParam @jakarta.validation.constraints.NotBlank @Size(max = 255) String organization,
        @RequestParam @jakarta.validation.constraints.NotBlank @Size(max = 255) String repository
    ) {
        return ApiResponse.ok(checksService().status(organization, repository));
    }

    @PostMapping("/integrations/github/checks/preview")
    public ApiResponse<GithubChecksSetupStatusDto> previewGithubChecks(
        @Valid @RequestBody GithubChecksPreviewRequest request
    ) {
        return ApiResponse.ok(checksService().preview(request));
    }

    @PutMapping("/integrations/github/checks/policy")
    public ApiResponse<GithubChecksSetupStatusDto> updateGithubChecksPolicy(
        @Valid @RequestBody GithubChecksPolicyRequest request,
        HttpServletRequest servletRequest
    ) {
        return ApiResponse.ok(checksService().setPolicy(
            request,
            RequestAuthentication.require(servletRequest).username()
        ));
    }

    private com.repoguard.agent.github.checks.GithubChecksSetupService checksService() {
        if (githubChecksSetupService == null) {
            throw new IllegalStateException("GitHub Checks setup service is unavailable");
        }
        return githubChecksSetupService;
    }

    @GetMapping("/integrations/mysql")
    public ApiResponse<ServiceIntegrationConfigDto> getMysqlIntegration() {
        return ApiResponse.ok(systemConfigService.getMysqlIntegration());
    }

    @PutMapping("/integrations/mysql")
    public ApiResponse<ServiceIntegrationConfigDto> updateMysqlIntegration(
        @Valid @RequestBody ServiceIntegrationConfigRequest request
    ) {
        return ApiResponse.ok(systemConfigService.updateMysqlIntegration(request));
    }

    @PostMapping("/integrations/mysql/test")
    public ApiResponse<ConnectionTestResultDto> testMysqlConnection(
        @Valid @RequestBody(required = false) ServiceIntegrationConfigRequest request
    ) {
        return ApiResponse.ok(systemConfigService.testMysqlConnection(request));
    }

    @GetMapping("/integrations/rabbitmq")
    public ApiResponse<ServiceIntegrationConfigDto> getRabbitMqIntegration() {
        return ApiResponse.ok(systemConfigService.getRabbitMqIntegration());
    }

    @PutMapping("/integrations/rabbitmq")
    public ApiResponse<ServiceIntegrationConfigDto> updateRabbitMqIntegration(
        @Valid @RequestBody ServiceIntegrationConfigRequest request
    ) {
        return ApiResponse.ok(systemConfigService.updateRabbitMqIntegration(request));
    }

    @PostMapping("/integrations/rabbitmq/test")
    public ApiResponse<ConnectionTestResultDto> testRabbitMqConnection(
        @Valid @RequestBody(required = false) ServiceIntegrationConfigRequest request
    ) {
        return ApiResponse.ok(systemConfigService.testRabbitMqConnection(request));
    }

    @GetMapping("/review-policy")
    public ApiResponse<ReviewPolicyConfigDto> getReviewPolicy() {
        return ApiResponse.ok(systemConfigService.getReviewPolicy());
    }

    @PutMapping("/review-policy")
    public ApiResponse<ReviewPolicyConfigDto> updateReviewPolicy(
        @Valid @RequestBody ReviewPolicyConfigRequest request
    ) {
        return ApiResponse.ok(systemConfigService.updateReviewPolicy(request));
    }

    @GetMapping("/system-settings")
    public ApiResponse<SystemSettingsDto> getSystemSettings() {
        return ApiResponse.ok(systemConfigService.getSystemSettings());
    }

    @PutMapping("/system-settings")
    public ApiResponse<SystemSettingsDto> updateSystemSettings(
        @Valid @RequestBody SystemSettingsRequest request
    ) {
        return ApiResponse.ok(systemConfigService.updateSystemSettings(request));
    }

    @RequireRole({"ADMIN", "PLATFORM_ADMIN", "RULE_ADMIN"})
    @GetMapping("/review-rules")
    public ApiResponse<ReviewRulesResponse> getReviewRules() {
        return ApiResponse.ok(systemConfigService.getReviewRules());
    }

    @RequireRole({"ADMIN", "PLATFORM_ADMIN", "RULE_ADMIN"})
    @PostMapping("/review-rules")
    public ApiResponse<ReviewRuleConfigDto> createReviewRule(
        @Valid @RequestBody ReviewRuleConfigRequest request
    ) {
        return ApiResponse.ok(systemConfigService.createReviewRule(request));
    }

    @RequireRole({"ADMIN", "PLATFORM_ADMIN", "RULE_ADMIN"})
    @PutMapping("/review-rules/{id}")
    public ApiResponse<ReviewRuleConfigDto> updateReviewRule(
        @PathVariable @Size(max = 64) String id,
        @RequestParam @Min(1) long expectedPolicyVersion,
        @Valid @RequestBody ReviewRuleConfigRequest request
    ) {
        return ApiResponse.ok(systemConfigService.updateReviewRule(id, request, expectedPolicyVersion));
    }

    @RequireRole({"ADMIN", "PLATFORM_ADMIN", "RULE_ADMIN"})
    @PutMapping("/review-rules/{id}/status")
    public ApiResponse<ReviewRuleConfigDto> updateReviewRuleStatus(
        @PathVariable @Size(max = 64) String id,
        @Valid @RequestBody ReviewRuleStatusRequest request
    ) {
        return ApiResponse.ok(systemConfigService.updateReviewRuleStatus(
            id,
            request.status(),
            request.expectedPolicyVersion()
        ));
    }

    @RequireRole({"ADMIN", "PLATFORM_ADMIN", "RULE_ADMIN"})
    @GetMapping("/review-rules/{id}/versions")
    public ApiResponse<PageResponse<ReviewRulePolicyVersionDto>> getReviewRuleVersions(
        @PathVariable @Size(max = 64) String id,
        @RequestParam(required = false) @Min(1) Long cursor,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return ApiResponse.ok(systemConfigService.getReviewRuleVersions(id, cursor, pageSize));
    }

    @RequireRole({"ADMIN", "PLATFORM_ADMIN", "RULE_ADMIN"})
    @PostMapping("/review-rules/{id}/versions/{policyVersion}/rollback")
    public ApiResponse<ReviewRuleConfigDto> rollbackReviewRule(
        @PathVariable @Size(max = 64) String id,
        @PathVariable @Min(1) long policyVersion,
        @Valid @RequestBody ReviewRuleRollbackRequest request
    ) {
        return ApiResponse.ok(systemConfigService.rollbackReviewRule(
            id,
            policyVersion,
            request.expectedPolicyVersion()
        ));
    }

    @GetMapping("/review-strategy")
    public ApiResponse<ReviewStrategyPolicyDto> getReviewStrategyPolicy() {
        return ApiResponse.ok(systemConfigService.getReviewStrategyPolicy());
    }

    @GetMapping("/review-strategy/versions")
    public ApiResponse<PageResponse<ReviewStrategyPolicyDto>> getReviewStrategyVersions(
        @RequestParam(required = false) @Min(1) Long cursor,
        @RequestParam(defaultValue = "20") @Min(1) @Max(100) int pageSize
    ) {
        return ApiResponse.ok(systemConfigService.getReviewStrategyVersions(cursor, pageSize));
    }

    @PutMapping("/review-strategy/enforcement")
    public ApiResponse<ReviewStrategyPolicyDto> promoteReviewStrategy(
        @Valid @RequestBody ReviewEnforcementModeRequest request
    ) {
        return ApiResponse.ok(systemConfigService.promoteReviewStrategy(
            request.enforcementMode(),
            request.expectedSnapshotId()
        ));
    }

    @PostMapping("/review-strategy/versions/{snapshotId}/rollback")
    public ApiResponse<ReviewStrategyPolicyDto> rollbackReviewStrategy(
        @PathVariable @Min(1) long snapshotId,
        @Valid @RequestBody ReviewStrategyRollbackRequest request
    ) {
        return ApiResponse.ok(systemConfigService.rollbackReviewStrategy(
            snapshotId,
            request.expectedSnapshotId()
        ));
    }

    @PostMapping("/review-policy/test")
    public ApiResponse<ConnectionTestResultDto> testReviewPolicy(
        @Valid @RequestBody(required = false) ReviewPolicyConfigRequest request
    ) {
        return ApiResponse.ok(systemConfigService.testReviewPolicy(request));
    }

    @PostMapping("/secrets/re-encryption")
    public ApiResponse<SecretReEncryptionJobDto> reEncryptSecrets(
        @Valid @RequestBody SecretReEncryptionRequest request,
        HttpServletRequest servletRequest
    ) {
        var operator = RequestAuthentication.require(servletRequest);
        return ApiResponse.ok(secretReEncryptionJobService.start(
            request,
            operator.id(),
            operator.username()
        ));
    }

    @GetMapping("/secrets/re-encryption/jobs/{jobId}")
    public ApiResponse<SecretReEncryptionJobDto> getSecretReEncryptionJob(
        @PathVariable @Min(1) Long jobId
    ) {
        return ApiResponse.ok(secretReEncryptionJobService.get(jobId));
    }

    @GetMapping("/secrets/re-encryption/jobs")
    public ApiResponse<PageResponse<SecretReEncryptionJobDto>> listSecretReEncryptionJobs(
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "20") @Min(1) @jakarta.validation.constraints.Max(100) int pageSize
    ) {
        return ApiResponse.ok(secretReEncryptionJobService.listJobs(page, pageSize));
    }

    @GetMapping("/secrets/re-encryption/jobs/{jobId}/items")
    public ApiResponse<PageResponse<SecretReEncryptionItemDto>> listSecretReEncryptionJobItems(
        @PathVariable @Min(1) Long jobId,
        @RequestParam(defaultValue = "1") @Min(1) int page,
        @RequestParam(defaultValue = "50") @Min(1) @jakarta.validation.constraints.Max(100) int pageSize
    ) {
        return ApiResponse.ok(secretReEncryptionJobService.listItems(jobId, page, pageSize));
    }

    @PostMapping("/secrets/re-encryption/jobs/{jobId}/pause")
    public ApiResponse<SecretReEncryptionJobDto> pauseSecretReEncryptionJob(
        @PathVariable @Min(1) Long jobId
    ) {
        return ApiResponse.ok(secretReEncryptionJobService.pause(jobId));
    }

    @PostMapping("/secrets/re-encryption/jobs/{jobId}/resume")
    public ApiResponse<SecretReEncryptionJobDto> resumeSecretReEncryptionJob(
        @PathVariable @Min(1) Long jobId
    ) {
        return ApiResponse.ok(secretReEncryptionJobService.resume(jobId));
    }
}
