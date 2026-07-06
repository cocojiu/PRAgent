package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.dto.ConnectionTestResultDto;
import com.repoguard.agent.dto.GithubIntegrationConfigRequest;
import com.repoguard.agent.dto.ReviewPolicyConfigRequest;
import com.repoguard.agent.dto.ServiceIntegrationConfigRequest;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import com.repoguard.agent.mapper.ReviewPolicyConfigMapper;
import com.repoguard.agent.security.SecretCryptoService;
import com.repoguard.agent.service.ConnectionTestService;
import com.repoguard.agent.review.LlmConnectionProbeResponseParser;
import javax.sql.DataSource;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

@Service
public class ConnectionTestServiceImpl implements ConnectionTestService {

    private static final String GITHUB_PROVIDER = GithubConnectionProbe.PROVIDER;
    private static final String MYSQL_PROVIDER = MysqlConnectionProbe.PROVIDER;
    private static final String RABBITMQ_PROVIDER = RabbitMqConnectionProbe.PROVIDER;
    private final IntegrationConfigMapper integrationConfigMapper;
    private final ReviewPolicyConfigMapper reviewPolicyConfigMapper;
    private final GithubConnectionProbe githubConnectionProbe;
    private final GithubIntegrationConnectionTestRunner githubConnectionTestRunner;
    private final LlmConnectionProbe llmConnectionProbe;
    private final LlmReviewPolicyConnectionTestRunner llmConnectionTestRunner;
    private final MysqlConnectionProbe mysqlConnectionProbe;
    private final RabbitMqConnectionProbe rabbitMqConnectionProbe;
    private final ServiceIntegrationConnectionTestRunner mysqlConnectionTestRunner;
    private final ServiceIntegrationConnectionTestRunner rabbitMqConnectionTestRunner;
    private final ConnectionTestConfigFactory configFactory;
    private final IntegrationConnectionCheckMarker connectionCheckMarker;

    public ConnectionTestServiceImpl(
        IntegrationConfigMapper integrationConfigMapper,
        ReviewPolicyConfigMapper reviewPolicyConfigMapper,
        RestClient.Builder restClientBuilder,
        ObjectMapper objectMapper,
        DataSource dataSource,
        RabbitTemplate rabbitTemplate,
        SecretCryptoService secretCryptoService,
        LlmConnectionProbeResponseParser llmResponseParser
    ) {
        this.integrationConfigMapper = integrationConfigMapper;
        this.reviewPolicyConfigMapper = reviewPolicyConfigMapper;
        this.githubConnectionProbe = new GithubConnectionProbe(restClientBuilder.clone(), secretCryptoService);
        this.githubConnectionTestRunner = new GithubIntegrationConnectionTestRunner(this.githubConnectionProbe);
        this.llmConnectionProbe = new LlmConnectionProbe(
            restClientBuilder.clone(),
            llmResponseParser,
            secretCryptoService
        );
        this.llmConnectionTestRunner = new LlmReviewPolicyConnectionTestRunner(this.llmConnectionProbe);
        this.mysqlConnectionProbe = new MysqlConnectionProbe(dataSource, secretCryptoService);
        this.rabbitMqConnectionProbe = new RabbitMqConnectionProbe(rabbitTemplate, secretCryptoService);
        this.mysqlConnectionTestRunner = new ServiceIntegrationConnectionTestRunner(
            "MySQL connection test succeeded",
            "MySQL runtime connection test succeeded",
            this.mysqlConnectionProbe::runtimeProbe,
            this.mysqlConnectionProbe
        );
        this.rabbitMqConnectionTestRunner = new ServiceIntegrationConnectionTestRunner(
            "RabbitMQ connection test succeeded",
            "RabbitMQ runtime connection test succeeded",
            this.rabbitMqConnectionProbe::runtimeProbe,
            this.rabbitMqConnectionProbe
        );
        this.configFactory = new ConnectionTestConfigFactory(secretCryptoService);
        this.connectionCheckMarker = new IntegrationConnectionCheckMarker(integrationConfigMapper);
    }

    @Override
    public ConnectionTestResultDto testGithubIntegration(GithubIntegrationConfigRequest configRequest) {
        IntegrationConfig savedConfig = findGithubConfig();
        boolean transientConfig = configRequest != null;
        IntegrationConfig config = transientConfig
            ? configFactory.githubIntegrationForTest(GITHUB_PROVIDER, configRequest, savedConfig)
            : savedConfig;
        return githubConnectionTestRunner.run(config, transientConfig, connectionCheckMarker::markChecked);
    }

    @Override
    public ConnectionTestResultDto testReviewPolicy(ReviewPolicyConfigRequest configRequest) {
        ReviewPolicyConfig savedConfig = findReviewPolicy();
        ReviewPolicyConfig config = configRequest == null ? savedConfig : configFactory.reviewPolicyForTest(configRequest, savedConfig);
        return llmConnectionTestRunner.run(config);
    }

    @Override
    public ConnectionTestResultDto testMysqlConnection(ServiceIntegrationConfigRequest configRequest) {
        IntegrationConfig savedConfig = findServiceIntegration(MYSQL_PROVIDER);
        boolean transientConfig = configRequest != null;
        IntegrationConfig config = transientConfig
            ? configFactory.serviceIntegrationForTest(MYSQL_PROVIDER, configRequest, savedConfig)
            : savedConfig;
        return mysqlConnectionTestRunner.run(savedConfig, config, transientConfig, connectionCheckMarker::markChecked);
    }

    @Override
    public ConnectionTestResultDto testRabbitMqConnection(ServiceIntegrationConfigRequest configRequest) {
        IntegrationConfig savedConfig = findServiceIntegration(RABBITMQ_PROVIDER);
        boolean transientConfig = configRequest != null;
        IntegrationConfig config = transientConfig
            ? configFactory.serviceIntegrationForTest(RABBITMQ_PROVIDER, configRequest, savedConfig)
            : savedConfig;
        return rabbitMqConnectionTestRunner.run(savedConfig, config, transientConfig, connectionCheckMarker::markChecked);
    }

    private IntegrationConfig findGithubConfig() {
        return integrationConfigMapper.selectOne(
            new LambdaQueryWrapper<IntegrationConfig>().eq(IntegrationConfig::getProvider, GITHUB_PROVIDER)
        );
    }

    private IntegrationConfig findServiceIntegration(String provider) {
        return integrationConfigMapper.selectOne(
            new LambdaQueryWrapper<IntegrationConfig>().eq(IntegrationConfig::getProvider, provider)
        );
    }

    private ReviewPolicyConfig findReviewPolicy() {
        return reviewPolicyConfigMapper.selectById(1L);
    }

}
