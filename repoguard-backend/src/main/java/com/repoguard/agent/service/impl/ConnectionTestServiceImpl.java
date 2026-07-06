package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.dto.ConnectionTestResultDto;
import com.repoguard.agent.dto.GithubIntegrationConfigRequest;
import com.repoguard.agent.dto.ReviewPolicyConfigRequest;
import com.repoguard.agent.dto.ServiceIntegrationConfigRequest;
import com.repoguard.agent.entity.IntegrationConfig;
import com.repoguard.agent.entity.ReviewPolicyConfig;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import com.repoguard.agent.mapper.ReviewPolicyConfigMapper;
import com.repoguard.agent.service.ConnectionTestService;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class ConnectionTestServiceImpl implements ConnectionTestService {

    private static final String GITHUB_PROVIDER = GithubConnectionProbe.PROVIDER;
    private static final String MYSQL_PROVIDER = MysqlConnectionProbe.PROVIDER;
    private static final String RABBITMQ_PROVIDER = RabbitMqConnectionProbe.PROVIDER;
    private final IntegrationConfigMapper integrationConfigMapper;
    private final ReviewPolicyConfigMapper reviewPolicyConfigMapper;
    private final GithubIntegrationConnectionTestRunner githubConnectionTestRunner;
    private final LlmReviewPolicyConnectionTestRunner llmConnectionTestRunner;
    private final ServiceIntegrationConnectionTestRunner mysqlConnectionTestRunner;
    private final ServiceIntegrationConnectionTestRunner rabbitMqConnectionTestRunner;
    private final ConnectionTestConfigFactory configFactory;
    private final IntegrationConnectionCheckMarker connectionCheckMarker;

    public ConnectionTestServiceImpl(
        IntegrationConfigMapper integrationConfigMapper,
        ReviewPolicyConfigMapper reviewPolicyConfigMapper,
        GithubIntegrationConnectionTestRunner githubConnectionTestRunner,
        LlmReviewPolicyConnectionTestRunner llmConnectionTestRunner,
        @Qualifier(ConnectionTestRunnerConfig.MYSQL_CONNECTION_TEST_RUNNER)
        ServiceIntegrationConnectionTestRunner mysqlConnectionTestRunner,
        @Qualifier(ConnectionTestRunnerConfig.RABBITMQ_CONNECTION_TEST_RUNNER)
        ServiceIntegrationConnectionTestRunner rabbitMqConnectionTestRunner,
        ConnectionTestConfigFactory configFactory,
        IntegrationConnectionCheckMarker connectionCheckMarker
    ) {
        this.integrationConfigMapper = Objects.requireNonNull(integrationConfigMapper, "integrationConfigMapper");
        this.reviewPolicyConfigMapper = Objects.requireNonNull(reviewPolicyConfigMapper, "reviewPolicyConfigMapper");
        this.githubConnectionTestRunner = Objects.requireNonNull(githubConnectionTestRunner, "githubConnectionTestRunner");
        this.llmConnectionTestRunner = Objects.requireNonNull(llmConnectionTestRunner, "llmConnectionTestRunner");
        this.mysqlConnectionTestRunner = Objects.requireNonNull(mysqlConnectionTestRunner, "mysqlConnectionTestRunner");
        this.rabbitMqConnectionTestRunner = Objects.requireNonNull(rabbitMqConnectionTestRunner, "rabbitMqConnectionTestRunner");
        this.configFactory = Objects.requireNonNull(configFactory, "configFactory");
        this.connectionCheckMarker = Objects.requireNonNull(connectionCheckMarker, "connectionCheckMarker");
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
