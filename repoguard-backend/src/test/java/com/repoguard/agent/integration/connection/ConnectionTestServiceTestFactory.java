package com.repoguard.agent.integration.connection;

import com.repoguard.agent.external.ExternalHttpResponseReader;
import com.repoguard.agent.mapper.IntegrationConfigMapper;
import com.repoguard.agent.mapper.ReviewPolicyConfigMapper;
import com.repoguard.agent.review.LlmConnectionProbeResponseParser;
import com.repoguard.agent.security.SecretCryptoService;
import org.springframework.web.client.RestClient;

public final class ConnectionTestServiceTestFactory {

    private ConnectionTestServiceTestFactory() {
    }

    public static ConnectionTestServiceImpl create(
        IntegrationConfigMapper integrationConfigMapper,
        ReviewPolicyConfigMapper reviewPolicyConfigMapper,
        SecretCryptoService secretCryptoService,
        LlmConnectionProbeResponseParser responseParser,
        ExternalHttpResponseReader responseReader
    ) {
        GithubConnectionProbe githubProbe = new GithubConnectionProbe(
            RestClient.builder(),
            secretCryptoService,
            responseReader
        );
        LlmConnectionProbe llmProbe = new LlmConnectionProbe(
            RestClient.builder(),
            responseParser,
            secretCryptoService,
            responseReader
        );
        MysqlConnectionProbe mysqlProbe = new MysqlConnectionProbe(null, secretCryptoService);
        RabbitMqConnectionProbe rabbitMqProbe = new RabbitMqConnectionProbe(
            null,
            new RabbitMqProbeConnectionFactory(secretCryptoService)
        );
        ConnectionTestConfigFactory configFactory = new ConnectionTestConfigFactory(secretCryptoService);
        IntegrationConnectionCheckMarker checkMarker = new IntegrationConnectionCheckMarker(integrationConfigMapper);

        return new ConnectionTestServiceImpl(
            new GithubIntegrationConnectionTestRunner(githubProbe),
            new GithubIntegrationConnectionTestExecutor(integrationConfigMapper, configFactory, checkMarker),
            new LlmReviewPolicyConnectionTestRunner(llmProbe),
            new ReviewPolicyConnectionTestExecutor(reviewPolicyConfigMapper, configFactory),
            new ServiceIntegrationConnectionTestRunner(
                "MySQL connection test succeeded",
                "MySQL runtime connection test succeeded",
                mysqlProbe::runtimeProbe,
                mysqlProbe
            ),
            new ServiceIntegrationConnectionTestRunner(
                "RabbitMQ connection test succeeded",
                "RabbitMQ runtime connection test succeeded",
                rabbitMqProbe::runtimeProbe,
                rabbitMqProbe
            ),
            new ServiceIntegrationConnectionTestExecutor(integrationConfigMapper, configFactory, checkMarker)
        );
    }
}
