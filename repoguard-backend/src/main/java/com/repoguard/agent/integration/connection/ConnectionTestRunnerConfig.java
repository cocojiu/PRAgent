package com.repoguard.agent.integration.connection;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class ConnectionTestRunnerConfig {

    static final String MYSQL_CONNECTION_TEST_RUNNER = "mysqlConnectionTestRunner";
    static final String RABBITMQ_CONNECTION_TEST_RUNNER = "rabbitMqConnectionTestRunner";

    @Bean
    GithubIntegrationConnectionTestRunner githubIntegrationConnectionTestRunner(
        GithubConnectionProbe githubConnectionProbe
    ) {
        return new GithubIntegrationConnectionTestRunner(githubConnectionProbe);
    }

    @Bean
    LlmReviewPolicyConnectionTestRunner llmReviewPolicyConnectionTestRunner(
        LlmConnectionProbe llmConnectionProbe
    ) {
        return new LlmReviewPolicyConnectionTestRunner(llmConnectionProbe);
    }

    @Bean(name = MYSQL_CONNECTION_TEST_RUNNER)
    ServiceIntegrationConnectionTestRunner mysqlConnectionTestRunner(MysqlConnectionProbe mysqlConnectionProbe) {
        return new ServiceIntegrationConnectionTestRunner(
            "MySQL connection test succeeded",
            "MySQL runtime connection test succeeded",
            mysqlConnectionProbe::runtimeProbe,
            mysqlConnectionProbe
        );
    }

    @Bean(name = RABBITMQ_CONNECTION_TEST_RUNNER)
    ServiceIntegrationConnectionTestRunner rabbitMqConnectionTestRunner(RabbitMqConnectionProbe rabbitMqConnectionProbe) {
        return new ServiceIntegrationConnectionTestRunner(
            "RabbitMQ connection test succeeded",
            "RabbitMQ runtime connection test succeeded",
            rabbitMqConnectionProbe::runtimeProbe,
            rabbitMqConnectionProbe
        );
    }
}
