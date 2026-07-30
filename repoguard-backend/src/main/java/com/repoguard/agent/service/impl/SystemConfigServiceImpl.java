package com.repoguard.agent.service.impl;

import com.repoguard.agent.dto.ConnectionTestResultDto;
import com.repoguard.agent.dto.GithubIntegrationConfigDto;
import com.repoguard.agent.dto.GithubIntegrationConfigRequest;
import com.repoguard.agent.dto.ReviewPolicyConfigDto;
import com.repoguard.agent.dto.ReviewPolicyConfigRequest;
import com.repoguard.agent.dto.ReviewRuleConfigDto;
import com.repoguard.agent.dto.ReviewRuleConfigRequest;
import com.repoguard.agent.dto.ReviewRulePolicyVersionDto;
import com.repoguard.agent.dto.ReviewRulesResponse;
import com.repoguard.agent.dto.ReviewStrategyPolicyDto;
import com.repoguard.agent.dto.ServiceIntegrationConfigDto;
import com.repoguard.agent.dto.ServiceIntegrationConfigRequest;
import com.repoguard.agent.dto.SystemSettingsDto;
import com.repoguard.agent.dto.SystemSettingsRequest;
import com.repoguard.agent.service.ConnectionTestService;
import com.repoguard.agent.service.ReviewPolicyConfigService;
import com.repoguard.agent.service.ReviewRuleConfigService;
import com.repoguard.agent.service.SystemConfigService;
import com.repoguard.agent.service.SystemIntegrationConfigService;
import com.repoguard.agent.service.SystemSettingsApplicationService;
import com.repoguard.agent.review.config.ReviewStrategyPolicyService;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class SystemConfigServiceImpl implements SystemConfigService {

    private final ConnectionTestService connectionTestService;
    private final SystemIntegrationConfigService systemIntegrationConfigService;
    private final ReviewPolicyConfigService reviewPolicyConfigService;
    private final ReviewRuleConfigService reviewRuleConfigService;
    private final ReviewStrategyPolicyService reviewStrategyPolicyService;
    private final SystemSettingsApplicationService systemSettingsApplicationService;

    @Autowired
    public SystemConfigServiceImpl(
        ConnectionTestService connectionTestService,
        SystemIntegrationConfigService systemIntegrationConfigService,
        ReviewPolicyConfigService reviewPolicyConfigService,
        ReviewRuleConfigService reviewRuleConfigService,
        ReviewStrategyPolicyService reviewStrategyPolicyService,
        SystemSettingsApplicationService systemSettingsApplicationService
    ) {
        this.connectionTestService = Objects.requireNonNull(connectionTestService, "connectionTestService");
        this.systemIntegrationConfigService = Objects.requireNonNull(
            systemIntegrationConfigService,
            "systemIntegrationConfigService"
        );
        this.reviewPolicyConfigService = Objects.requireNonNull(
            reviewPolicyConfigService,
            "reviewPolicyConfigService"
        );
        this.reviewRuleConfigService = Objects.requireNonNull(reviewRuleConfigService, "reviewRuleConfigService");
        this.reviewStrategyPolicyService = Objects.requireNonNull(
            reviewStrategyPolicyService,
            "reviewStrategyPolicyService"
        );
        this.systemSettingsApplicationService = Objects.requireNonNull(
            systemSettingsApplicationService,
            "systemSettingsApplicationService"
        );
    }

    public SystemConfigServiceImpl(
        ConnectionTestService connectionTestService,
        SystemIntegrationConfigService systemIntegrationConfigService,
        ReviewPolicyConfigService reviewPolicyConfigService,
        ReviewRuleConfigService reviewRuleConfigService,
        SystemSettingsApplicationService systemSettingsApplicationService
    ) {
        this.connectionTestService = Objects.requireNonNull(connectionTestService, "connectionTestService");
        this.systemIntegrationConfigService = Objects.requireNonNull(
            systemIntegrationConfigService,
            "systemIntegrationConfigService"
        );
        this.reviewPolicyConfigService = Objects.requireNonNull(
            reviewPolicyConfigService,
            "reviewPolicyConfigService"
        );
        this.reviewRuleConfigService = Objects.requireNonNull(reviewRuleConfigService, "reviewRuleConfigService");
        this.reviewStrategyPolicyService = null;
        this.systemSettingsApplicationService = Objects.requireNonNull(
            systemSettingsApplicationService,
            "systemSettingsApplicationService"
        );
    }

    @Override
    public GithubIntegrationConfigDto getGithubIntegration() {
        return systemIntegrationConfigService.getGithubIntegration();
    }

    @Override
    public GithubIntegrationConfigDto updateGithubIntegration(GithubIntegrationConfigRequest request) {
        return systemIntegrationConfigService.updateGithubIntegration(request);
    }

    @Override
    public ServiceIntegrationConfigDto getMysqlIntegration() {
        return systemIntegrationConfigService.getMysqlIntegration();
    }

    @Override
    public ServiceIntegrationConfigDto updateMysqlIntegration(ServiceIntegrationConfigRequest request) {
        return systemIntegrationConfigService.updateMysqlIntegration(request);
    }

    @Override
    public ServiceIntegrationConfigDto getRabbitMqIntegration() {
        return systemIntegrationConfigService.getRabbitMqIntegration();
    }

    @Override
    public ServiceIntegrationConfigDto updateRabbitMqIntegration(ServiceIntegrationConfigRequest request) {
        return systemIntegrationConfigService.updateRabbitMqIntegration(request);
    }

    @Override
    public ReviewPolicyConfigDto getReviewPolicy() {
        return reviewPolicyConfigService.getReviewPolicy();
    }

    @Override
    public ReviewPolicyConfigDto updateReviewPolicy(ReviewPolicyConfigRequest request) {
        return reviewPolicyConfigService.updateReviewPolicy(request);
    }

    @Override
    public SystemSettingsDto getSystemSettings() {
        return systemSettingsApplicationService.getSystemSettings();
    }

    @Override
    public SystemSettingsDto updateSystemSettings(SystemSettingsRequest request) {
        return systemSettingsApplicationService.updateSystemSettings(request);
    }

    @Override
    public ReviewRulesResponse getReviewRules() {
        return reviewRuleConfigService.getReviewRules();
    }

    @Override
    public ReviewRuleConfigDto createReviewRule(ReviewRuleConfigRequest request) {
        return reviewRuleConfigService.createReviewRule(request);
    }

    @Override
    public ReviewRuleConfigDto updateReviewRule(String id, ReviewRuleConfigRequest request) {
        return reviewRuleConfigService.updateReviewRule(id, request);
    }

    @Override
    public ReviewRuleConfigDto updateReviewRuleStatus(String id, String status) {
        return reviewRuleConfigService.updateReviewRuleStatus(id, status);
    }

    @Override
    public List<ReviewRulePolicyVersionDto> getReviewRuleVersions(String id) {
        return reviewRuleConfigService.getReviewRuleVersions(id);
    }

    @Override
    public ReviewRuleConfigDto rollbackReviewRule(String id, long policyVersion) {
        return reviewRuleConfigService.rollbackReviewRule(id, policyVersion);
    }

    @Override
    public ReviewStrategyPolicyDto getReviewStrategyPolicy() {
        return requireReviewStrategyPolicyService().getActive();
    }

    @Override
    public List<ReviewStrategyPolicyDto> getReviewStrategyVersions() {
        return requireReviewStrategyPolicyService().list();
    }

    @Override
    public ReviewStrategyPolicyDto promoteReviewStrategy(String enforcementMode) {
        return requireReviewStrategyPolicyService().promote(enforcementMode);
    }

    @Override
    public ReviewStrategyPolicyDto rollbackReviewStrategy(long snapshotId) {
        return requireReviewStrategyPolicyService().rollback(snapshotId);
    }

    private ReviewStrategyPolicyService requireReviewStrategyPolicyService() {
        if (reviewStrategyPolicyService == null) {
            throw new IllegalStateException("reviewStrategyPolicyService");
        }
        return reviewStrategyPolicyService;
    }

    @Override
    public ConnectionTestResultDto testGithubIntegration(GithubIntegrationConfigRequest configRequest) {
        return connectionTestService.testGithubIntegration(configRequest);
    }

    @Override
    public ConnectionTestResultDto testReviewPolicy(ReviewPolicyConfigRequest configRequest) {
        return connectionTestService.testReviewPolicy(configRequest);
    }

    @Override
    public ConnectionTestResultDto testMysqlConnection(ServiceIntegrationConfigRequest configRequest) {
        return connectionTestService.testMysqlConnection(configRequest);
    }

    @Override
    public ConnectionTestResultDto testRabbitMqConnection(ServiceIntegrationConfigRequest configRequest) {
        return connectionTestService.testRabbitMqConnection(configRequest);
    }

}
