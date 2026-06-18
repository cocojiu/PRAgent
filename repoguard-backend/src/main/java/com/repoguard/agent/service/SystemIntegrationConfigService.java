package com.repoguard.agent.service;

import com.repoguard.agent.dto.GithubIntegrationConfigDto;
import com.repoguard.agent.dto.GithubIntegrationConfigRequest;
import com.repoguard.agent.dto.ServiceIntegrationConfigDto;
import com.repoguard.agent.dto.ServiceIntegrationConfigRequest;

public interface SystemIntegrationConfigService {

    GithubIntegrationConfigDto getGithubIntegration();

    GithubIntegrationConfigDto updateGithubIntegration(GithubIntegrationConfigRequest request);

    ServiceIntegrationConfigDto getMysqlIntegration();

    ServiceIntegrationConfigDto updateMysqlIntegration(ServiceIntegrationConfigRequest request);

    ServiceIntegrationConfigDto getRabbitMqIntegration();

    ServiceIntegrationConfigDto updateRabbitMqIntegration(ServiceIntegrationConfigRequest request);
}
