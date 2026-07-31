package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.config.ApiRuntimeEnabled;
import com.repoguard.agent.dto.ConnectionTestResultDto;
import com.repoguard.agent.dto.GithubIntegrationConfigDto;
import com.repoguard.agent.dto.GithubIntegrationConfigRequest;
import com.repoguard.agent.dto.ReviewPolicyConfigDto;
import com.repoguard.agent.dto.ReviewPolicyConfigRequest;
import com.repoguard.agent.dto.ReviewRuleConfigDto;
import com.repoguard.agent.dto.ReviewRuleConfigRequest;
import com.repoguard.agent.dto.ReviewRulePolicyVersionDto;
import com.repoguard.agent.dto.ReviewRuleStatusRequest;
import com.repoguard.agent.dto.ReviewRulesResponse;
import com.repoguard.agent.dto.ReviewStrategyPolicyDto;
import com.repoguard.agent.dto.ReviewEnforcementModeRequest;
import com.repoguard.agent.dto.SecretReEncryptionRequest;
import com.repoguard.agent.dto.SecretReEncryptionResponse;
import com.repoguard.agent.dto.ServiceIntegrationConfigDto;
import com.repoguard.agent.dto.ServiceIntegrationConfigRequest;
import com.repoguard.agent.dto.SystemSettingsDto;
import com.repoguard.agent.dto.SystemSettingsRequest;
import com.repoguard.agent.security.RequireRole;
import com.repoguard.agent.security.SecretReEncryptionService;
import com.repoguard.agent.service.SystemConfigService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/config")
@ApiRuntimeEnabled
@RequireRole("ADMIN")
public class SystemConfigController {

    private final SystemConfigService systemConfigService;
    private final SecretReEncryptionService secretReEncryptionService;

    public SystemConfigController(
        SystemConfigService systemConfigService,
        SecretReEncryptionService secretReEncryptionService
    ) {
        this.systemConfigService = systemConfigService;
        this.secretReEncryptionService = secretReEncryptionService;
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

    @GetMapping("/review-rules")
    public ApiResponse<ReviewRulesResponse> getReviewRules() {
        return ApiResponse.ok(systemConfigService.getReviewRules());
    }

    @PostMapping("/review-rules")
    public ApiResponse<ReviewRuleConfigDto> createReviewRule(
        @Valid @RequestBody ReviewRuleConfigRequest request
    ) {
        return ApiResponse.ok(systemConfigService.createReviewRule(request));
    }

    @PutMapping("/review-rules/{id}")
    public ApiResponse<ReviewRuleConfigDto> updateReviewRule(
        @PathVariable @Size(max = 64) String id,
        @Valid @RequestBody ReviewRuleConfigRequest request
    ) {
        return ApiResponse.ok(systemConfigService.updateReviewRule(id, request));
    }

    @PutMapping("/review-rules/{id}/status")
    public ApiResponse<ReviewRuleConfigDto> updateReviewRuleStatus(
        @PathVariable @Size(max = 64) String id,
        @Valid @RequestBody ReviewRuleStatusRequest request
    ) {
        return ApiResponse.ok(systemConfigService.updateReviewRuleStatus(id, request.status()));
    }

    @GetMapping("/review-rules/{id}/versions")
    public ApiResponse<List<ReviewRulePolicyVersionDto>> getReviewRuleVersions(
        @PathVariable @Size(max = 64) String id
    ) {
        return ApiResponse.ok(systemConfigService.getReviewRuleVersions(id));
    }

    @PostMapping("/review-rules/{id}/versions/{policyVersion}/rollback")
    public ApiResponse<ReviewRuleConfigDto> rollbackReviewRule(
        @PathVariable @Size(max = 64) String id,
        @PathVariable @Min(1) long policyVersion
    ) {
        return ApiResponse.ok(systemConfigService.rollbackReviewRule(id, policyVersion));
    }

    @GetMapping("/review-strategy")
    public ApiResponse<ReviewStrategyPolicyDto> getReviewStrategyPolicy() {
        return ApiResponse.ok(systemConfigService.getReviewStrategyPolicy());
    }

    @GetMapping("/review-strategy/versions")
    public ApiResponse<List<ReviewStrategyPolicyDto>> getReviewStrategyVersions() {
        return ApiResponse.ok(systemConfigService.getReviewStrategyVersions());
    }

    @PutMapping("/review-strategy/enforcement")
    public ApiResponse<ReviewStrategyPolicyDto> promoteReviewStrategy(
        @Valid @RequestBody ReviewEnforcementModeRequest request
    ) {
        return ApiResponse.ok(systemConfigService.promoteReviewStrategy(request.enforcementMode()));
    }

    @PostMapping("/review-strategy/versions/{snapshotId}/rollback")
    public ApiResponse<ReviewStrategyPolicyDto> rollbackReviewStrategy(
        @PathVariable @Min(1) long snapshotId
    ) {
        return ApiResponse.ok(systemConfigService.rollbackReviewStrategy(snapshotId));
    }

    @PostMapping("/review-policy/test")
    public ApiResponse<ConnectionTestResultDto> testReviewPolicy(
        @Valid @RequestBody(required = false) ReviewPolicyConfigRequest request
    ) {
        return ApiResponse.ok(systemConfigService.testReviewPolicy(request));
    }

    @PostMapping("/secrets/re-encryption")
    public ApiResponse<SecretReEncryptionResponse> reEncryptSecrets(
        @Valid @RequestBody SecretReEncryptionRequest request
    ) {
        return ApiResponse.ok(secretReEncryptionService.reEncrypt(request));
    }
}
