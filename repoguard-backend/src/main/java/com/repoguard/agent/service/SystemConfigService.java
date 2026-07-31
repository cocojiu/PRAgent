package com.repoguard.agent.service;

import com.repoguard.agent.dto.GithubIntegrationConfigDto;
import com.repoguard.agent.dto.GithubIntegrationConfigRequest;
import com.repoguard.agent.dto.ConnectionTestResultDto;
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
import java.util.List;

public interface SystemConfigService {

    GithubIntegrationConfigDto getGithubIntegration();

    GithubIntegrationConfigDto updateGithubIntegration(GithubIntegrationConfigRequest request);

    ServiceIntegrationConfigDto getMysqlIntegration();

    ServiceIntegrationConfigDto updateMysqlIntegration(ServiceIntegrationConfigRequest request);

    ServiceIntegrationConfigDto getRabbitMqIntegration();

    ServiceIntegrationConfigDto updateRabbitMqIntegration(ServiceIntegrationConfigRequest request);

    ReviewPolicyConfigDto getReviewPolicy();

    ReviewPolicyConfigDto updateReviewPolicy(ReviewPolicyConfigRequest request);

    SystemSettingsDto getSystemSettings();

    SystemSettingsDto updateSystemSettings(SystemSettingsRequest request);

    ReviewRulesResponse getReviewRules();

    ReviewRuleConfigDto createReviewRule(ReviewRuleConfigRequest request);

    ReviewRuleConfigDto updateReviewRule(String id, ReviewRuleConfigRequest request);

    ReviewRuleConfigDto updateReviewRuleStatus(String id, String status);

    default List<ReviewRulePolicyVersionDto> getReviewRuleVersions(String id) {
        return List.of();
    }

    default ReviewRuleConfigDto rollbackReviewRule(String id, long policyVersion) {
        return null;
    }

    default ReviewStrategyPolicyDto getReviewStrategyPolicy() {
        return null;
    }

    default List<ReviewStrategyPolicyDto> getReviewStrategyVersions() {
        return List.of();
    }

    default ReviewStrategyPolicyDto promoteReviewStrategy(String enforcementMode) {
        return null;
    }

    default ReviewStrategyPolicyDto rollbackReviewStrategy(long snapshotId) {
        return null;
    }

    ConnectionTestResultDto testGithubIntegration(GithubIntegrationConfigRequest request);

    ConnectionTestResultDto testReviewPolicy(ReviewPolicyConfigRequest request);

    ConnectionTestResultDto testMysqlConnection(ServiceIntegrationConfigRequest request);

    ConnectionTestResultDto testRabbitMqConnection(ServiceIntegrationConfigRequest request);
}
