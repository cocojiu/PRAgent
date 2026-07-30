package com.repoguard.agent.integration.connection;

import com.repoguard.agent.dto.ConnectionTestResultDto;
import com.repoguard.agent.dto.GithubIntegrationConfigRequest;
import com.repoguard.agent.dto.ReviewPolicyConfigRequest;
import com.repoguard.agent.dto.ServiceIntegrationConfigRequest;
import com.repoguard.agent.service.ConnectionTestService;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

@Service
public class ConnectionTestServiceImpl implements ConnectionTestService {

    private static final String GITHUB_PROVIDER = GithubConnectionProbe.PROVIDER;
    private static final String MYSQL_PROVIDER = MysqlConnectionProbe.PROVIDER;
    private static final String RABBITMQ_PROVIDER = RabbitMqConnectionProbe.PROVIDER;
    private final GithubIntegrationConnectionTestRunner githubConnectionTestRunner;
    private final GithubIntegrationConnectionTestExecutor githubIntegrationConnectionTestExecutor;
    private final LlmReviewPolicyConnectionTestRunner llmConnectionTestRunner;
    private final ReviewPolicyConnectionTestExecutor reviewPolicyConnectionTestExecutor;
    private final ServiceIntegrationConnectionTestRunner mysqlConnectionTestRunner;
    private final ServiceIntegrationConnectionTestRunner rabbitMqConnectionTestRunner;
    private final ServiceIntegrationConnectionTestExecutor serviceIntegrationConnectionTestExecutor;

    public ConnectionTestServiceImpl(
        GithubIntegrationConnectionTestRunner githubConnectionTestRunner,
        GithubIntegrationConnectionTestExecutor githubIntegrationConnectionTestExecutor,
        LlmReviewPolicyConnectionTestRunner llmConnectionTestRunner,
        ReviewPolicyConnectionTestExecutor reviewPolicyConnectionTestExecutor,
        @Qualifier(ConnectionTestRunnerConfig.MYSQL_CONNECTION_TEST_RUNNER)
        ServiceIntegrationConnectionTestRunner mysqlConnectionTestRunner,
        @Qualifier(ConnectionTestRunnerConfig.RABBITMQ_CONNECTION_TEST_RUNNER)
        ServiceIntegrationConnectionTestRunner rabbitMqConnectionTestRunner,
        ServiceIntegrationConnectionTestExecutor serviceIntegrationConnectionTestExecutor
    ) {
        this.githubConnectionTestRunner = Objects.requireNonNull(githubConnectionTestRunner, "githubConnectionTestRunner");
        this.githubIntegrationConnectionTestExecutor = Objects.requireNonNull(
            githubIntegrationConnectionTestExecutor,
            "githubIntegrationConnectionTestExecutor"
        );
        this.llmConnectionTestRunner = Objects.requireNonNull(llmConnectionTestRunner, "llmConnectionTestRunner");
        this.reviewPolicyConnectionTestExecutor = Objects.requireNonNull(
            reviewPolicyConnectionTestExecutor,
            "reviewPolicyConnectionTestExecutor"
        );
        this.mysqlConnectionTestRunner = Objects.requireNonNull(mysqlConnectionTestRunner, "mysqlConnectionTestRunner");
        this.rabbitMqConnectionTestRunner = Objects.requireNonNull(rabbitMqConnectionTestRunner, "rabbitMqConnectionTestRunner");
        this.serviceIntegrationConnectionTestExecutor = Objects.requireNonNull(
            serviceIntegrationConnectionTestExecutor,
            "serviceIntegrationConnectionTestExecutor"
        );
    }

    @Override
    public ConnectionTestResultDto testGithubIntegration(GithubIntegrationConfigRequest configRequest) {
        return githubIntegrationConnectionTestExecutor.test(GITHUB_PROVIDER, configRequest, githubConnectionTestRunner);
    }

    @Override
    public ConnectionTestResultDto testReviewPolicy(ReviewPolicyConfigRequest configRequest) {
        return reviewPolicyConnectionTestExecutor.test(configRequest, llmConnectionTestRunner);
    }

    @Override
    public ConnectionTestResultDto testMysqlConnection(ServiceIntegrationConfigRequest configRequest) {
        return serviceIntegrationConnectionTestExecutor.test(MYSQL_PROVIDER, configRequest, mysqlConnectionTestRunner);
    }

    @Override
    public ConnectionTestResultDto testRabbitMqConnection(ServiceIntegrationConfigRequest configRequest) {
        return serviceIntegrationConnectionTestExecutor.test(RABBITMQ_PROVIDER, configRequest, rabbitMqConnectionTestRunner);
    }

}
