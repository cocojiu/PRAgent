package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.dto.ConnectionTestResultDto;
import com.repoguard.agent.dto.GithubIntegrationConfigDto;
import com.repoguard.agent.dto.GithubIntegrationConfigRequest;
import com.repoguard.agent.dto.ReviewPolicyConfigDto;
import com.repoguard.agent.dto.ReviewPolicyConfigRequest;
import com.repoguard.agent.dto.ReviewRuleConfigDto;
import com.repoguard.agent.dto.ReviewRuleConfigRequest;
import com.repoguard.agent.dto.ReviewRuleStatusRequest;
import com.repoguard.agent.dto.ReviewRulesResponse;
import com.repoguard.agent.dto.SystemSettingsDto;
import com.repoguard.agent.dto.SystemSettingsRequest;
import com.repoguard.agent.service.SystemConfigService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/config")
public class SystemConfigController {

    private final SystemConfigService systemConfigService;

    public SystemConfigController(SystemConfigService systemConfigService) {
        this.systemConfigService = systemConfigService;
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
    public ApiResponse<ConnectionTestResultDto> testGithubIntegration() {
        return ApiResponse.ok(systemConfigService.testGithubIntegration());
    }

    @PostMapping("/integrations/mysql/test")
    public ApiResponse<ConnectionTestResultDto> testMysqlConnection() {
        return ApiResponse.ok(systemConfigService.testMysqlConnection());
    }

    @PostMapping("/integrations/rabbitmq/test")
    public ApiResponse<ConnectionTestResultDto> testRabbitMqConnection() {
        return ApiResponse.ok(systemConfigService.testRabbitMqConnection());
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

    @PostMapping("/review-policy/test")
    public ApiResponse<ConnectionTestResultDto> testReviewPolicy() {
        return ApiResponse.ok(systemConfigService.testReviewPolicy());
    }
}
