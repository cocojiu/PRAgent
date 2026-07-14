package com.repoguard.agent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.RepoGuardApplication;
import com.repoguard.agent.controller.ReviewController;
import com.repoguard.agent.worker.ReviewTaskWorker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

@EnabledIfEnvironmentVariable(named = "REPOGUARD_RUN_INTEGRATION_TESTS", matches = "true")
class ProductionRuntimeContextIntegrationTest {

    @Test
    void apiOnlyContextRunsAllMigrationsAndExcludesWorkers() {
        try (ConfigurableApplicationContext context = start(true, false)) {
            assertThat(context.getBeansOfType(ReviewController.class)).hasSize(1);
            assertThat(context.getBeansOfType(ReviewTaskWorker.class)).isEmpty();
            assertProductionInfrastructure(context);
        }
    }

    @Test
    void workerOnlyContextRunsAllMigrationsAndExcludesApiControllers() {
        try (ConfigurableApplicationContext context = start(false, true)) {
            assertThat(context.getBeansOfType(ReviewController.class)).isEmpty();
            assertThat(context.getBeansOfType(ReviewTaskWorker.class)).hasSize(1);
            assertProductionInfrastructure(context);
        }
    }

    private ConfigurableApplicationContext start(boolean apiEnabled, boolean workerEnabled) {
        return new SpringApplicationBuilder(RepoGuardApplication.class)
            .web(WebApplicationType.SERVLET)
            .profiles("prod")
            .run(
                "--app.runtime.api.enabled=" + apiEnabled,
                "--app.runtime.worker.enabled=" + workerEnabled,
                "--app.github.webhook.enabled=false",
                "--app.security.admin-api-key.enabled=false",
                "--app.cors.allowed-origins[0]=https://integration.local",
                "--server.port=0",
                "--spring.main.banner-mode=off",
                "--spring.task.scheduling.enabled=false"
            );
    }

    private void assertProductionInfrastructure(ConfigurableApplicationContext context) {
        WebServerApplicationContext webContext = (WebServerApplicationContext) context;
        assertThat(webContext.getWebServer().getPort()).isPositive();
        assertThat(context.getBean(JdbcTemplate.class).queryForObject("select 1", Integer.class)).isEqualTo(1);
        Boolean rabbitOpen = context.getBean(RabbitTemplate.class).execute(channel -> channel.isOpen());
        assertThat(rabbitOpen).isTrue();
    }
}
