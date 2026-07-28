package com.repoguard.agent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repoguard.agent.RepoGuardApplication;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.controller.ReviewController;
import com.repoguard.agent.dto.AuthRefreshRequest;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.security.AuthTokenService;
import com.repoguard.agent.service.AuthService;
import com.repoguard.agent.service.impl.ReviewTaskTransitionStore;
import com.repoguard.agent.worker.ReviewTaskClaimService;
import com.repoguard.agent.worker.ReviewTaskWorker;
import ch.qos.logback.classic.Logger;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
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
        try (ConfigurableApplicationContext context = start("api")) {
            assertThat(context.getBeansOfType(ReviewController.class)).hasSize(1);
            assertThat(context.getBeansOfType(ReviewTaskWorker.class)).isEmpty();
            assertProductionInfrastructure(context);
        }
    }

    @Test
    void workerOnlyContextRunsAllMigrationsAndExcludesApiControllers() {
        try (ConfigurableApplicationContext context = start("worker")) {
            assertThat(context.getBeansOfType(ReviewController.class)).isEmpty();
            assertThat(context.getBeansOfType(ReviewTaskWorker.class)).hasSize(1);
            assertProductionInfrastructure(context);
        }
    }

    @Test
    void refreshTokenReuseInvalidationCommitsBeforeUnauthorizedResponse() {
        try (ConfigurableApplicationContext context = start("api")) {
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

    @Test
    void reviewTaskTransitionsUseRealMysqlCasAndExplicitNullWrites() throws Exception {
        try (ConfigurableApplicationContext context = start("api")) {
            JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);
            ReviewTaskMapper reviewTaskMapper = context.getBean(ReviewTaskMapper.class);
            ReviewTaskTransitionStore transitionStore = context.getBean(ReviewTaskTransitionStore.class);
            ReviewTaskClaimService claimService = context.getBean(ReviewTaskClaimService.class);
            String organization = "transition-" + Long.toUnsignedString(System.nanoTime());

            try {
                Long retryTaskId = insertReviewTask(
                    jdbcTemplate,
                    organization,
                    9101,
                    "FAILED",
                    true,
                    "REJECTED"
                );
                populateRetryResidue(jdbcTemplate, retryTaskId);
                ReviewTask retryFirst = reviewTaskMapper.selectById(retryTaskId);
                ReviewTask retrySecond = reviewTaskMapper.selectById(retryTaskId);

                assertThat(runConcurrently(
                    () -> transitionStore.retryFailedTask(retryFirst, 3),
                    () -> transitionStore.retryFailedTask(retrySecond, 3)
                )).isOne();
                assertRetryStateWasCleared(jdbcTemplate, retryTaskId);

                Long supersededTaskId = insertReviewTask(
                    jdbcTemplate,
                    organization,
                    9105,
                    "SUPERSEDED",
                    false,
                    "NOT_REQUIRED"
                );
                populateRetryResidue(jdbcTemplate, supersededTaskId);
                ReviewTask supersededFirst = reviewTaskMapper.selectById(supersededTaskId);
                ReviewTask supersededSecond = reviewTaskMapper.selectById(supersededTaskId);
                String latestCommit = "fedcba9876543210fedcba9876543210fedcba98";

                assertThat(runConcurrently(
                    () -> transitionStore.retryReviewTask(supersededFirst, 3, latestCommit),
                    () -> transitionStore.retryReviewTask(supersededSecond, 3, latestCommit)
                )).isOne();
                assertRetryStateWasCleared(jdbcTemplate, supersededTaskId);
                assertThat(jdbcTemplate.queryForObject(
                    "select commit_sha from review_task where id = ?",
                    String.class,
                    supersededTaskId
                )).isEqualTo(latestCommit);

                Long humanTaskId = insertReviewTask(
                    jdbcTemplate,
                    organization,
                    9102,
                    "PENDING_HUMAN_REVIEW",
                    true,
                    "PENDING"
                );
                ReviewTask humanFirst = reviewTaskMapper.selectById(humanTaskId);
                ReviewTask humanSecond = reviewTaskMapper.selectById(humanTaskId);
                LocalDateTime reviewedAt = LocalDateTime.now();

                assertThat(runConcurrently(
                    () -> transitionStore.completeHumanReview(
                        humanFirst,
                        "APPROVED",
                        "APPROVED",
                        "approved",
                        "reviewer-a",
                        reviewedAt
                    ),
                    () -> transitionStore.completeHumanReview(
                        humanSecond,
                        "REJECTED",
                        "REJECTED",
                        "rejected",
                        "reviewer-b",
                        reviewedAt
                    )
                )).isOne();
                Map<String, Object> humanRow = jdbcTemplate.queryForMap(
                    "select status, human_review_status from review_task where id = ?",
                    humanTaskId
                );
                assertThat(humanRow.get("human_review_status")).isIn("APPROVED", "REJECTED");
                assertThat(humanRow.get("status")).isEqualTo(humanRow.get("human_review_status"));

                Long requeueTaskId = insertReviewTask(
                    jdbcTemplate,
                    organization,
                    9103,
                    "PUBLISH_FAILED",
                    false,
                    "NOT_REQUIRED"
                );
                jdbcTemplate.update(
                    "update review_task set last_publish_error = 'old publish error' where id = ?",
                    requeueTaskId
                );
                ReviewTask requeueFirst = reviewTaskMapper.selectById(requeueTaskId);
                ReviewTask requeueSecond = reviewTaskMapper.selectById(requeueTaskId);

                assertThat(runConcurrently(
                    () -> transitionStore.requeueForPublish(requeueFirst),
                    () -> transitionStore.requeueForPublish(requeueSecond)
                )).isOne();
                Map<String, Object> requeueRow = jdbcTemplate.queryForMap(
                    "select status, last_publish_error from review_task where id = ?",
                    requeueTaskId
                );
                assertThat(requeueRow.get("status")).isEqualTo("QUEUED");
                assertThat(requeueRow.get("last_publish_error")).isNull();

                Long terminalTaskId = insertReviewTask(
                    jdbcTemplate,
                    organization,
                    9104,
                    "REVIEWING",
                    false,
                    "NOT_REQUIRED"
                );
                LocalDateTime terminalStartedAt = LocalDateTime.now().minusMinutes(1);
                jdbcTemplate.update("""
                    update review_task
                    set started_at = ?, review_claimed_at = ?, review_claimed_by = 'claim-terminal'
                    where id = ?
                    """, terminalStartedAt, terminalStartedAt, terminalTaskId);
                ReviewTask terminalTask = reviewTaskMapper.selectById(terminalTaskId);
                ReviewTask staleTerminalTask = reviewTaskMapper.selectById(terminalTaskId);
                terminalTask.setStatus("COMPLETED");
                terminalTask.setRiskLevel("LOW");
                terminalTask.setLlmStatus("COMPLETED");
                terminalTask.setLlmProvider("openai");
                terminalTask.setLlmModel("gpt-terminal");
                terminalTask.setLlmPromptTokens(50);
                terminalTask.setLlmCompletionTokens(10);
                terminalTask.setLlmTotalTokens(60);
                terminalTask.setLlmEstimatedCost(new BigDecimal("0.012345"));
                terminalTask.setFinishedAt(LocalDateTime.now());
                terminalTask.setDurationSeconds(60);

                assertThat(claimService.writeTerminalStateIfClaimOwned(
                    terminalTask,
                    "claim-terminal"
                )).isTrue();
                assertThat(claimService.writeTerminalStateIfClaimOwned(
                    staleTerminalTask,
                    "claim-terminal"
                )).isFalse();
                Map<String, Object> terminalRow = jdbcTemplate.queryForMap("""
                    select status, risk_level, llm_status, llm_provider, llm_model,
                           llm_prompt_tokens, llm_estimated_cost,
                           finished_at, duration_seconds, review_claimed_at, review_claimed_by
                    from review_task
                    where id = ?
                    """, terminalTaskId);
                assertThat(terminalRow)
                    .containsEntry("status", "COMPLETED")
                    .containsEntry("risk_level", "LOW")
                    .containsEntry("llm_status", "COMPLETED")
                    .containsEntry("llm_provider", "openai")
                    .containsEntry("llm_model", "gpt-terminal");
                assertThat(terminalRow.get("review_claimed_at")).isNull();
                assertThat(terminalRow.get("review_claimed_by")).isNull();
            } finally {
                jdbcTemplate.update("delete from review_task where organization = ?", organization);
            }
        }
    }

    private ConfigurableApplicationContext start(String runtimeRole) {
        return new SpringApplicationBuilder(RepoGuardApplication.class)
            .web(WebApplicationType.SERVLET)
            .profiles("prod")
            .run(
                "--app.runtime.role=" + runtimeRole,
                "--app.runtime.api.instance-count=" + ("worker".equals(runtimeRole) ? 0 : 1),
                "--app.github.webhook.enabled=false",
                "--app.security.admin-api-key.enabled=false",
                "--app.cors.allowed-origins[0]=https://integration.local",
                "--server.port=0",
                "--spring.main.banner-mode=off",
                "--spring.task.scheduling.enabled=false"
            );
    }

    private Long insertReviewTask(
        JdbcTemplate jdbcTemplate,
        String organization,
        int prNumber,
        String status,
        boolean humanReviewRequired,
        String humanReviewStatus
    ) {
        jdbcTemplate.update("""
            insert into review_task (
                pr_number, title, repository, organization, commit_sha, branch_name,
                status, risk_level, mq_retries, publish_attempts, llm_status, pr_url,
                source, trigger_source, human_review_required, human_review_status,
                created_at, duration_seconds
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """,
            prNumber,
            "Transition integration task " + prNumber,
            "transition-tests",
            organization,
            "0123456789abcdef0123456789abcdef01234567",
            "integration",
            status,
            "HIGH",
            2,
            4,
            "FAILED",
            "https://example.invalid/pull/" + prNumber,
            "MANUAL_INPUT",
            "MANUAL_INPUT",
            humanReviewRequired,
            humanReviewStatus,
            LocalDateTime.now(),
            120
        );
        return jdbcTemplate.queryForObject(
            "select id from review_task where organization = ? and pr_number = ?",
            Long.class,
            organization,
            prNumber
        );
    }

    private void populateRetryResidue(JdbcTemplate jdbcTemplate, Long taskId) {
        LocalDateTime residueAt = LocalDateTime.now().minusMinutes(5);
        jdbcTemplate.update("""
            update review_task
            set next_publish_retry_at = ?,
                last_publish_error = 'old publish error',
                publish_claimed_at = ?,
                publish_claimed_by = 'old-publisher',
                review_claimed_at = ?,
                review_claimed_by = 'old-reviewer',
                llm_provider = 'openai',
                llm_model = 'gpt-old',
                llm_duration_ms = 1234,
                llm_parse_status = 'parsed',
                llm_fallback_reason = 'old fallback',
                llm_prompt_summary = 'old prompt',
                llm_prompt_tokens = 100,
                llm_completion_tokens = 20,
                llm_total_tokens = 120,
                llm_estimated_cost = 1.234567,
                human_review_note = 'old decision',
                human_review_by = 'old-reviewer',
                human_reviewed_at = ?,
                started_at = ?,
                finished_at = ?
            where id = ?
            """,
            residueAt.plusMinutes(1),
            residueAt,
            residueAt,
            residueAt,
            residueAt.minusMinutes(2),
            residueAt.plusMinutes(2),
            taskId
        );
    }

    private void assertRetryStateWasCleared(JdbcTemplate jdbcTemplate, Long taskId) {
        Map<String, Object> row = jdbcTemplate.queryForMap("""
            select status, risk_level, mq_retries, publish_attempts,
                   next_publish_retry_at, last_publish_error,
                   publish_claimed_at, publish_claimed_by,
                   review_claimed_at, review_claimed_by,
                   llm_status, llm_provider, llm_model, llm_duration_ms,
                   llm_parse_status, llm_fallback_reason, llm_prompt_summary,
                   llm_prompt_tokens, llm_completion_tokens, llm_total_tokens,
                   llm_estimated_cost, human_review_required, human_review_status,
                   human_review_note, human_review_by, human_reviewed_at,
                   started_at, finished_at, duration_seconds
            from review_task
            where id = ?
            """, taskId);

        assertThat(row.get("status")).isEqualTo("QUEUED");
        assertThat(row.get("risk_level")).isEqualTo("INFO");
        assertThat(((Number) row.get("mq_retries")).intValue()).isEqualTo(3);
        assertThat(((Number) row.get("publish_attempts")).intValue()).isZero();
        assertThat(row.get("llm_status")).isEqualTo("PENDING");
        assertThat(String.valueOf(row.get("human_review_required")).toLowerCase()).isIn("0", "false");
        assertThat(row.get("human_review_status")).isEqualTo("NOT_REQUIRED");
        assertThat(((Number) row.get("duration_seconds")).intValue()).isZero();
        assertThat(row).extractingByKeys(
            "next_publish_retry_at",
            "last_publish_error",
            "publish_claimed_at",
            "publish_claimed_by",
            "review_claimed_at",
            "review_claimed_by",
            "llm_provider",
            "llm_model",
            "llm_duration_ms",
            "llm_parse_status",
            "llm_fallback_reason",
            "llm_prompt_summary",
            "llm_prompt_tokens",
            "llm_completion_tokens",
            "llm_total_tokens",
            "llm_estimated_cost",
            "human_review_note",
            "human_review_by",
            "human_reviewed_at",
            "started_at",
            "finished_at"
        ).containsOnlyNulls();
    }

    private int runConcurrently(TransitionAction first, TransitionAction second) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Boolean>> results = List.of(first, second).stream()
                .map(action -> executor.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to start transition race");
                    }
                    try {
                        action.run();
                        return true;
                    } catch (BusinessException ex) {
                        assertThat(ex.getErrorCode()).isEqualTo(com.repoguard.agent.common.ErrorCode.CONFLICT);
                        assertThat(ex).hasMessage(ReviewTaskTransitionStore.STATE_CHANGED_MESSAGE);
                        return false;
                    }
                }))
                .toList();
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();
            int successes = 0;
            for (Future<Boolean> result : results) {
                if (result.get(10, TimeUnit.SECONDS)) {
                    successes++;
                }
            }
            return successes;
        } finally {
            executor.shutdownNow();
        }
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

    @FunctionalInterface
    private interface TransitionAction {
        void run();
    }
}
