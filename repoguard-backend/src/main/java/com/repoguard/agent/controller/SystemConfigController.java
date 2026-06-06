package com.repoguard.agent.controller;

import com.repoguard.agent.common.ApiResponse;
import com.repoguard.agent.dto.ConnectionTestResultDto;
import com.repoguard.agent.dto.GithubIntegrationConfigDto;
import com.repoguard.agent.dto.GithubIntegrationConfigRequest;
import com.repoguard.agent.dto.ReviewPolicyConfigDto;
import com.repoguard.agent.dto.ReviewPolicyConfigRequest;
import com.repoguard.agent.service.SystemConfigService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
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

    @PostMapping("/review-policy/test")
    public ApiResponse<ConnectionTestResultDto> testReviewPolicy() {
        return ApiResponse.ok(systemConfigService.testReviewPolicy());
    }
}
