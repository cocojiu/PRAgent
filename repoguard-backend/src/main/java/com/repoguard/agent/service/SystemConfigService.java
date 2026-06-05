package com.repoguard.agent.service;

import com.repoguard.agent.dto.GithubIntegrationConfigDto;
import com.repoguard.agent.dto.GithubIntegrationConfigRequest;
import com.repoguard.agent.dto.ReviewPolicyConfigDto;
import com.repoguard.agent.dto.ReviewPolicyConfigRequest;

public interface SystemConfigService {

    GithubIntegrationConfigDto getGithubIntegration();

    GithubIntegrationConfigDto updateGithubIntegration(GithubIntegrationConfigRequest request);

    ReviewPolicyConfigDto getReviewPolicy();

    ReviewPolicyConfigDto updateReviewPolicy(ReviewPolicyConfigRequest request);
}
