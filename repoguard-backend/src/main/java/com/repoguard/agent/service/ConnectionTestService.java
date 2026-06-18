package com.repoguard.agent.service;

import com.repoguard.agent.dto.ConnectionTestResultDto;
import com.repoguard.agent.dto.GithubIntegrationConfigRequest;
import com.repoguard.agent.dto.ReviewPolicyConfigRequest;
import com.repoguard.agent.dto.ServiceIntegrationConfigRequest;

public interface ConnectionTestService {

    ConnectionTestResultDto testGithubIntegration(GithubIntegrationConfigRequest request);

    ConnectionTestResultDto testReviewPolicy(ReviewPolicyConfigRequest request);

    ConnectionTestResultDto testMysqlConnection(ServiceIntegrationConfigRequest request);

    ConnectionTestResultDto testRabbitMqConnection(ServiceIntegrationConfigRequest request);
}
