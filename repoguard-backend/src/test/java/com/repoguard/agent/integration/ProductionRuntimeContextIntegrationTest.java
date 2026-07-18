package com.repoguard.agent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repoguard.agent.RepoGuardApplication;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.controller.ReviewController;
import com.repoguard.agent.dto.AuthRefreshRequest;
import com.repoguard.agent.security.AuthTokenService;
import com.repoguard.agent.service.AuthService;
import com.repoguard.agent.worker.ReviewTaskWorker;
import ch.qos.logback.classic.Logger;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
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

    @Test
    void refreshTokenReuseInvalidationCommitsBeforeUnauthorizedResponse() {
        try (ConfigurableApplicationContext context = start(true, false)) {
            JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);
            AuthService authService = context.getBean(AuthService.class);
            AuthTokenService authTokenService = context.getBean(AuthTokenService.class);
            String suffix = Long.toUnsignedString(System.nanoTime());
            String username = "refresh-reuse-" + suffix;
            String email = username + "@integration.local";
            String reusedToken = "reused-refresh-" + suffix;
            String activeToken = "active-refresh-" + suffix;
            LocalDateTime now = LocalDateTime.now();

            try {
                jdbcTemplate.update("""
                    insert into user_account (
                        username, email, password_hash, role, status, failed_login_count,
                        locked_until, session_version, last_login_at, created_at, updated_at
                    ) values (?, ?, ?, 'VIEWER', 'ACTIVE', 0, null, 7, null, ?, ?)
                    """, username, email, "integration-password-hash", now, now);
                Long userId = jdbcTemplate.queryForObject(
                    "select id from user_account where username = ?",
                    Long.class,
                    username
                );
                jdbcTemplate.update("""
                    insert into user_refresh_token (
                        user_id, token_hash, session_version, status, expires_at,
                        revoked_at, last_used_at, created_at, updated_at
                    ) values (?, ?, 7, 'REVOKED', ?, ?, null, ?, ?)
                    """,
                    userId,
                    authTokenService.hashRefreshToken(reusedToken),
                    now.plusHours(1),
                    now,
                    now,
                    now
                );
                jdbcTemplate.update("""
                    insert into user_refresh_token (
                        user_id, token_hash, session_version, status, expires_at,
                        revoked_at, last_used_at, created_at, updated_at
                    ) values (?, ?, 7, 'ACTIVE', ?, null, null, ?, ?)
                    """,
                    userId,
                    authTokenService.hashRefreshToken(activeToken),
                    now.plusHours(1),
                    now,
                    now
                );

                assertThatThrownBy(() -> authService.refresh(new AuthRefreshRequest(reusedToken)))
                    .isInstanceOf(BusinessException.class)
                    .hasMessage("登录状态已过期，请重新登录");

                assertThat(jdbcTemplate.queryForObject(
                    "select session_version from user_account where id = ?",
                    Integer.class,
                    userId
                )).isEqualTo(8);
                assertThat(jdbcTemplate.queryForList(
                    "select status from user_refresh_token where user_id = ? order by id",
                    String.class,
                    userId
                )).containsOnly("REVOKED");
                assertThat(jdbcTemplate.queryForObject(
                    "select count(*) from user_refresh_token where user_id = ? and last_used_at is not null",
                    Integer.class,
                    userId
                )).isEqualTo(1);
                assertThat(jdbcTemplate.queryForObject("""
                    select count(*)
                    from user_login_audit
                    where user_id = ?
                      and event_type = 'TOKEN_REFRESH'
                      and result = 'FAILURE'
                      and failure_reason = 'refresh token reuse detected'
                    """, Integer.class, userId)).isEqualTo(1);
            } finally {
                jdbcTemplate.update("""
                    delete from user_login_audit
                    where user_id in (select id from user_account where username = ?)
                    """, username);
                jdbcTemplate.update("""
                    delete from user_refresh_token
                    where user_id in (select id from user_account where username = ?)
                    """, username);
                jdbcTemplate.update("delete from user_account where username = ?", username);
            }
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

        Logger rootLogger = (Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        assertThat(rootLogger.getAppender("CONSOLE")).isNotNull();
        assertThat(rootLogger.getAppender("ROLLING_FILE")).isNull();
    }
}
