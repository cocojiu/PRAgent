package com.repoguard.agent.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.dto.ConnectionTestResultDto;
import com.repoguard.agent.dto.GithubIntegrationConfigDto;
import com.repoguard.agent.dto.GithubIntegrationConfigRequest;
import com.repoguard.agent.dto.ReviewPolicyConfigDto;
import com.repoguard.agent.dto.ReviewPolicyConfigRequest;
import com.repoguard.agent.dto.ReviewRuleConfigDto;
import com.repoguard.agent.dto.ReviewRuleConfigRequest;
import com.repoguard.agent.dto.ReviewRulesResponse;
import com.repoguard.agent.dto.ServiceIntegrationConfigDto;
import com.repoguard.agent.dto.ServiceIntegrationConfigRequest;
import com.repoguard.agent.dto.SystemSettingsDto;
import com.repoguard.agent.dto.SystemSettingsRequest;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewPolicyConfigMapper;
import com.repoguard.agent.mapper.ReviewRuleConfigMapper;
import com.repoguard.agent.mapper.SystemSettingLogMapper;
import com.repoguard.agent.mapper.SystemSettingsConfigMapper;
import com.repoguard.agent.security.SecretCryptoService;
import com.repoguard.agent.service.ConnectionTestService;
import com.repoguard.agent.service.ReviewPolicyConfigService;
import com.repoguard.agent.service.ReviewRuleConfigService;
import com.repoguard.agent.service.SystemConfigService;
import com.repoguard.agent.service.SystemIntegrationConfigService;
import com.repoguard.agent.service.SystemSettingsApplicationService;
import javax.sql.DataSource;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

@Service
public class SystemConfigServiceImpl implements SystemConfigService {

    private final ConnectionTestService connectionTestService;
    private final SystemIntegrationConfigService systemIntegrationConfigService;
    private final ReviewPolicyConfigService reviewPolicyConfigService;
    private final ReviewRuleConfigService reviewRuleConfigService;
    private final SystemSettingsApplicationService systemSettingsApplicationService;

    @Autowired
    public SystemConfigServiceImpl(
        ConnectionTestService connectionTestService,
        SystemIntegrationConfigService systemIntegrationConfigService,
        ReviewPolicyConfigService reviewPolicyConfigService,
        ReviewRuleConfigService reviewRuleConfigService,
        SystemSettingsApplicationService systemSettingsApplicationService
    ) {
        this.connectionTestService = connectionTestService;
        this.systemIntegrationConfigService = systemIntegrationConfigService;
        this.reviewPolicyConfigService = reviewPolicyConfigService;
        this.reviewRuleConfigService = reviewRuleConfigService;
        this.systemSettingsApplicationService = systemSettingsApplicationService;
    }

    public SystemConfigServiceImpl(
        IntegrationConfigMapper integrationConfigMapper,
        ReviewPolicyConfigMapper reviewPolicyConfigMapper,
        ReviewRuleConfigMapper reviewRuleConfigMapper,
        ReviewFindingMapper reviewFindingMapper,
        SystemSettingsConfigMapper systemSettingsConfigMapper,
        SystemSettingLogMapper systemSettingLogMapper,
        RestClient.Builder restClientBuilder,
        ObjectMapper objectMapper,
        DataSource dataSource,
        RabbitTemplate rabbitTemplate,
        SecretCryptoService secretCryptoService,
        Environment environment
    ) {
        this(
            new ConnectionTestServiceImpl(
                integrationConfigMapper,
                reviewPolicyConfigMapper,
                restClientBuilder,
                objectMapper,
                dataSource,
                rabbitTemplate,
                secretCryptoService
            ),
            new SystemIntegrationConfigServiceImpl(
                integrationConfigMapper,
                secretCryptoService,
                environment,
                null
            ),
            new ReviewPolicyConfigServiceImpl(
                reviewPolicyConfigMapper,
                secretCryptoService
            ),
            new ReviewRuleConfigServiceImpl(
                reviewRuleConfigMapper,
                reviewFindingMapper,
                null,
                new ReviewRuleConfigPolicy(),
                new ReviewRuleMetricAssembler()
            ),
            new SystemSettingsApplicationServiceImpl(
                systemSettingsConfigMapper,
                systemSettingLogMapper,
                reviewPolicyConfigMapper
            )
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
    @Transactional
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
