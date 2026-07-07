package com.repoguard.agent.service.impl;

import com.repoguard.agent.dto.ConnectionTestResultDto;
import com.repoguard.agent.dto.GithubIntegrationConfigRequest;
import com.repoguard.agent.dto.ReviewPolicyConfigRequest;
import com.repoguard.agent.dto.ServiceIntegrationConfigRequest;
import com.repoguard.agent.entity.ReviewPolicyConfig;
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
    private final ReviewPolicyConfigMapper reviewPolicyConfigMapper;
    private final GithubIntegrationConnectionTestRunner githubConnectionTestRunner;
    private final GithubIntegrationConnectionTestExecutor githubIntegrationConnectionTestExecutor;
    private final LlmReviewPolicyConnectionTestRunner llmConnectionTestRunner;
    private final ServiceIntegrationConnectionTestRunner mysqlConnectionTestRunner;
    private final ServiceIntegrationConnectionTestRunner rabbitMqConnectionTestRunner;
    private final ServiceIntegrationConnectionTestExecutor serviceIntegrationConnectionTestExecutor;
    private final ConnectionTestConfigFactory configFactory;

    public ConnectionTestServiceImpl(
        ReviewPolicyConfigMapper reviewPolicyConfigMapper,
        GithubIntegrationConnectionTestRunner githubConnectionTestRunner,
        GithubIntegrationConnectionTestExecutor githubIntegrationConnectionTestExecutor,
        LlmReviewPolicyConnectionTestRunner llmConnectionTestRunner,
        @Qualifier(ConnectionTestRunnerConfig.MYSQL_CONNECTION_TEST_RUNNER)
        ServiceIntegrationConnectionTestRunner mysqlConnectionTestRunner,
        @Qualifier(ConnectionTestRunnerConfig.RABBITMQ_CONNECTION_TEST_RUNNER)
        ServiceIntegrationConnectionTestRunner rabbitMqConnectionTestRunner,
        ServiceIntegrationConnectionTestExecutor serviceIntegrationConnectionTestExecutor,
        ConnectionTestConfigFactory configFactory
    ) {
        this.reviewPolicyConfigMapper = Objects.requireNonNull(reviewPolicyConfigMapper, "reviewPolicyConfigMapper");
        this.githubConnectionTestRunner = Objects.requireNonNull(githubConnectionTestRunner, "githubConnectionTestRunner");
        this.githubIntegrationConnectionTestExecutor = Objects.requireNonNull(
            githubIntegrationConnectionTestExecutor,
            "githubIntegrationConnectionTestExecutor"
        );
        this.llmConnectionTestRunner = Objects.requireNonNull(llmConnectionTestRunner, "llmConnectionTestRunner");
        this.mysqlConnectionTestRunner = Objects.requireNonNull(mysqlConnectionTestRunner, "mysqlConnectionTestRunner");
        this.rabbitMqConnectionTestRunner = Objects.requireNonNull(rabbitMqConnectionTestRunner, "rabbitMqConnectionTestRunner");
        this.serviceIntegrationConnectionTestExecutor = Objects.requireNonNull(
            serviceIntegrationConnectionTestExecutor,
            "serviceIntegrationConnectionTestExecutor"
        );
        this.configFactory = Objects.requireNonNull(configFactory, "configFactory");
    }

    @Override
    public ConnectionTestResultDto testGithubIntegration(GithubIntegrationConfigRequest configRequest) {
        return githubIntegrationConnectionTestExecutor.test(GITHUB_PROVIDER, configRequest, githubConnectionTestRunner);
    }

    @Override
    public ConnectionTestResultDto testReviewPolicy(ReviewPolicyConfigRequest configRequest) {
        ReviewPolicyConfig savedConfig = findReviewPolicy();
        ReviewPolicyConfig config = configRequest == null ? savedConfig : configFactory.reviewPolicyForTest(configRequest, savedConfig);
        return llmConnectionTestRunner.run(config);
    }

    @Override
    public ConnectionTestResultDto testMysqlConnection(ServiceIntegrationConfigRequest configRequest) {
        return serviceIntegrationConnectionTestExecutor.test(MYSQL_PROVIDER, configRequest, mysqlConnectionTestRunner);
    }

    @Override
    public ConnectionTestResultDto testRabbitMqConnection(ServiceIntegrationConfigRequest configRequest) {
        return serviceIntegrationConnectionTestExecutor.test(RABBITMQ_PROVIDER, configRequest, rabbitMqConnectionTestRunner);
    }

    private ReviewPolicyConfig findReviewPolicy() {
        return reviewPolicyConfigMapper.selectById(1L);
    }

}
