package com.repoguard.agent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repoguard.agent.RepoGuardApplication;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.controller.ReviewController;
import com.repoguard.agent.dto.AuthLogoutRequest;
import com.repoguard.agent.dto.AuthPasswordChangeRequest;
import com.repoguard.agent.dto.AuthRefreshRequest;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewCalibrationQueueMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.projection.ReviewCalibrationProjections.Sample;
import com.repoguard.agent.mapper.projection.ReviewCalibrationProjections.Summary;
import com.repoguard.agent.service.NotificationDispatchService;
import com.repoguard.agent.notification.publish.NotificationEventPublishCompensator;
import com.repoguard.agent.security.AuthAccountCache;
import com.repoguard.agent.security.AuthTokenService;
import com.repoguard.agent.security.PasswordHashService;
import com.repoguard.agent.security.DatabaseRateLimitWindowStore;
import com.repoguard.agent.service.AuthService;
import com.repoguard.agent.security.AuthTokenFilter;
import com.repoguard.agent.review.task.ReviewTaskTransitionStore;
import com.repoguard.agent.review.quality.ReviewQualityBaseline;
import com.repoguard.agent.review.quality.ReviewQualityBaselineService;
import com.repoguard.agent.worker.ReviewTaskClaimService;
import com.repoguard.agent.worker.ReviewTaskWorker;
import ch.qos.logback.classic.Logger;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.ServletException;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.slf4j.LoggerFactory;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@EnabledIfEnvironmentVariable(named = "REPOGUARD_RUN_INTEGRATION_TESTS", matches = "true")
class ProductionRuntimeContextIntegrationTest {

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(
        ProductionRuntimeContextIntegrationTest.class
    );

    @Test
    void databaseRateLimitStoreSupportsSharedApiScaleOutAndTargetRps() {
        String[] scaleOutArguments = {
            "--app.runtime.api.instance-count=2",
            "--app.security.rate-limit-store=database"
        };
        try (ConfigurableApplicationContext instanceA = start(
            "api",
            scaleOutArguments
        ); ConfigurableApplicationContext instanceB = start("api", scaleOutArguments)) {
            JdbcTemplate jdbcTemplate = instanceA.getBean(JdbcTemplate.class);
            DatabaseRateLimitWindowStore storeA = instanceA.getBean(DatabaseRateLimitWindowStore.class);
            DatabaseRateLimitWindowStore storeB = instanceB.getBean(DatabaseRateLimitWindowStore.class);
            String suffix = Long.toUnsignedString(System.nanoTime());
            String scope = "integration-atomic-" + suffix;
            List<String> capacityScopes = List.of(
                "integration-single-5-" + suffix,
                "integration-single-10-" + suffix,
                "integration-dual-5-" + suffix,
                "integration-dual-10-" + suffix
            );
            long minute = System.currentTimeMillis() / 60_000L;
            try {
                assertThat(storeA.tryAcquire(scope, "same-client", minute, 2)).isTrue();
                assertThat(storeB.tryAcquire(scope, "same-client", minute, 2)).isTrue();
                assertThat(storeA.tryAcquire(scope, "same-client", minute, 2)).isFalse();
                assertThat(jdbcTemplate.queryForObject(
                    "select request_count from api_rate_limit_window where rate_limit_scope = ?",
                    Long.class,
                    scope
                )).isEqualTo(3L);

                List<RateLimitCapacitySample> samples = List.of(
                    measureRateLimitCapacity(List.of(storeA), capacityScopes.get(0), minute, 5),
                    measureRateLimitCapacity(List.of(storeA), capacityScopes.get(1), minute, 10),
                    measureRateLimitCapacity(List.of(storeA, storeB), capacityScopes.get(2), minute, 5),
                    measureRateLimitCapacity(List.of(storeA, storeB), capacityScopes.get(3), minute, 10)
                );
                assertThat(samples).allSatisfy(sample -> {
                    assertThat(sample.allowedRequests()).isEqualTo(sample.targetRps());
                    assertThat(sample.p95Nanos()).isLessThan(TimeUnit.SECONDS.toNanos(1));
                    assertThat(sample.p99Nanos()).isLessThan(TimeUnit.SECONDS.toNanos(1));
                    LOGGER.info(
                        "Shared rate-limit capacity evidence instances={} targetRps={} requests={} p95Ms={} p99Ms={}",
                        sample.instanceCount(),
                        sample.targetRps(),
                        sample.allowedRequests(),
                        nanosToMillis(sample.p95Nanos()),
                        nanosToMillis(sample.p99Nanos())
                    );
                });
                for (int index = 0; index < capacityScopes.size(); index++) {
                    assertThat(jdbcTemplate.queryForObject(
                        "select request_count from api_rate_limit_window where rate_limit_scope = ?",
                        Long.class,
                        capacityScopes.get(index)
                    )).isEqualTo(index % 2 == 0 ? 5L : 10L);
                }
                assertThat(databaseFailureCount(instanceA.getBean(MeterRegistry.class))).isZero();
                assertThat(databaseFailureCount(instanceB.getBean(MeterRegistry.class))).isZero();
            } finally {
                jdbcTemplate.update("delete from api_rate_limit_window where rate_limit_scope = ?", scope);
                for (String capacityScope : capacityScopes) {
                    jdbcTemplate.update("delete from api_rate_limit_window where rate_limit_scope = ?", capacityScope);
                }
            }
        }
    }

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
    void twoApiInstancesRejectStaleAccessTokensAfterCrossInstanceSessionChanges() throws Exception {
        String[] scaleOutArguments = {
            "--app.runtime.api.instance-count=2",
            "--app.security.rate-limit-store=database"
        };
        try (ConfigurableApplicationContext instanceA = start("api", scaleOutArguments);
             ConfigurableApplicationContext instanceB = start("api", scaleOutArguments)) {
            assertThat(instanceA.getBean(AuthAccountCache.class).isCachingEnabled()).isFalse();
            assertThat(instanceB.getBean(AuthAccountCache.class).isCachingEnabled()).isFalse();

            JdbcTemplate jdbcTemplate = instanceA.getBean(JdbcTemplate.class);
            PasswordHashService passwordHashService = instanceA.getBean(PasswordHashService.class);
            AuthTokenService authTokenService = instanceA.getBean(AuthTokenService.class);
            AuthService authService = instanceA.getBean(AuthService.class);
            FilterRegistrationBean<?> instanceBFilterRegistration = instanceB
                .getBean("authTokenFilterRegistration", FilterRegistrationBean.class);
            AuthTokenFilter instanceBFilter = (AuthTokenFilter) instanceBFilterRegistration.getFilter();
            List<Long> userIds = new ArrayList<>();
            try {
                LocalDateTime now = LocalDateTime.now();
                String suffix = Long.toUnsignedString(System.nanoTime());
                String currentPassword = "CurrentPassword1";
                String newPassword = "NewPassword2";

                Long passwordUserId = insertAuthAccount(
                    jdbcTemplate,
                    "r1-password-" + suffix,
                    "r1-password-" + suffix + "@integration.local",
                    passwordHashService.hash(currentPassword),
                    "ACTIVE",
                    1,
                    now
                );
                userIds.add(passwordUserId);
                String passwordToken = authTokenService.issueAccessToken(
                    passwordUserId,
                    "r1-password-" + suffix,
                    "VIEWER",
                    1
                ).token();
                assertApiInstanceAllows(instanceBFilter, passwordToken);

                authService.changePassword(
                    passwordUserId,
                    new AuthPasswordChangeRequest(currentPassword, newPassword, newPassword)
                );
                assertApiInstanceRejects(instanceBFilter, passwordToken);

                Long disabledUserId = insertAuthAccount(
                    jdbcTemplate,
                    "r1-disabled-" + suffix,
                    "r1-disabled-" + suffix + "@integration.local",
                    passwordHashService.hash(currentPassword),
                    "ACTIVE",
                    1,
                    now
                );
                userIds.add(disabledUserId);
                String disabledToken = authTokenService.issueAccessToken(
                    disabledUserId,
                    "r1-disabled-" + suffix,
                    "VIEWER",
                    1
                ).token();
                assertApiInstanceAllows(instanceBFilter, disabledToken);
                jdbcTemplate.update(
                    "update user_account set status = 'DISABLED', updated_at = ? where id = ?",
                    LocalDateTime.now(),
                    disabledUserId
                );
                assertApiInstanceRejects(instanceBFilter, disabledToken);

                Long logoutUserId = insertAuthAccount(
                    jdbcTemplate,
                    "r1-logout-" + suffix,
                    "r1-logout-" + suffix + "@integration.local",
                    passwordHashService.hash(currentPassword),
                    "ACTIVE",
                    1,
                    now
                );
                userIds.add(logoutUserId);
                AuthTokenService.TokenIssue refreshToken = authTokenService.issueRefreshToken(false);
                insertRefreshToken(jdbcTemplate, logoutUserId, refreshToken, authTokenService);
                String logoutToken = authTokenService.issueAccessToken(
                    logoutUserId,
                    "r1-logout-" + suffix,
                    "VIEWER",
                    1
                ).token();
                assertApiInstanceAllows(instanceBFilter, logoutToken);

                authService.logout(new AuthLogoutRequest(refreshToken.token()));
                assertApiInstanceRejects(instanceBFilter, logoutToken);
            } finally {
                for (Long userId : userIds) {
                    jdbcTemplate.update("delete from user_login_audit where user_id = ?", userId);
                    jdbcTemplate.update("delete from user_refresh_token where user_id = ?", userId);
                    jdbcTemplate.update("delete from user_account where id = ?", userId);
                }
            }
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

    @Test
    void reviewQualityBaselineUsesRealMysqlAndExplicitFeedbackSemantics() {
        try (ConfigurableApplicationContext context = start("api")) {
            JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);
            ReviewQualityBaselineService baselineService = context.getBean(ReviewQualityBaselineService.class);
            ReviewCalibrationQueueMapper calibrationQueueMapper =
                context.getBean(ReviewCalibrationQueueMapper.class);
            String suffix = Long.toUnsignedString(System.nanoTime());
            String organization = "quality-baseline-" + suffix;
            String repository = "quality-baseline-repository-" + suffix;
            String ruleId = "RG-GOLDEN-" + suffix;
            List<Long> taskIds = new ArrayList<>();
            Long taskId = null;

            ReviewQualityBaseline before = baselineService.loadBaseline();
            try {
                taskId = insertReviewTask(
                    jdbcTemplate,
                    organization,
                    9150,
                    "COMPLETED",
                    false,
                    "NOT_REQUIRED"
                );
                taskIds.add(taskId);
                jdbcTemplate.update("""
                    update review_task
                    set repository = ?,
                        assessment_status = 'COMPLETE',
                        finished_at = ?,
                        duration_seconds = 17,
                        llm_estimated_cost = 0.123400
                    where id = ?
                    """, repository, LocalDateTime.now(), taskId);

                insertQualityFinding(jdbcTemplate, taskId, ruleId, "HIGH", 10, "VALID", "duplicate");
                insertQualityFinding(jdbcTemplate, taskId, ruleId, "HIGH", 10, "VALID", "duplicate");
                insertQualityFinding(jdbcTemplate, taskId, ruleId, "HIGH", 20, "FIXED", "fixed");
                insertQualityFinding(jdbcTemplate, taskId, ruleId, "HIGH", 30, "FALSE_POSITIVE", "false-positive");
                insertQualityFinding(jdbcTemplate, taskId, ruleId, "HIGH", null, "IGNORED", "ignored");
                insertQualityFinding(jdbcTemplate, taskId, ruleId, "LOW", 50, "UNREVIEWED", "pending");

                List<String> excludedAssessmentStatuses = List.of("PARTIAL", "FAILED", "SUPERSEDED");
                List<String> excludedTaskStatuses = List.of("COMPLETED", "FAILED", "SUPERSEDED");
                for (int index = 0; index < excludedAssessmentStatuses.size(); index++) {
                    Long excludedTaskId = insertReviewTask(
                        jdbcTemplate,
                        organization,
                        9151 + index,
                        excludedTaskStatuses.get(index),
                        false,
                        "NOT_REQUIRED"
                    );
                    taskIds.add(excludedTaskId);
                    jdbcTemplate.update("""
                        update review_task
                        set repository = ?,
                            assessment_status = ?,
                            finished_at = ?,
                            duration_seconds = 9,
                            llm_estimated_cost = 0.100000
                        where id = ?
                        """,
                        repository,
                        excludedAssessmentStatuses.get(index),
                        LocalDateTime.now(),
                        excludedTaskId
                    );
                    insertQualityFinding(
                        jdbcTemplate,
                        excludedTaskId,
                        ruleId,
                        "HIGH",
                        60 + index,
                        "VALID",
                        "excluded-" + excludedAssessmentStatuses.get(index).toLowerCase(java.util.Locale.ROOT)
                    );
                }

                // This fixture writes directly through JdbcTemplate and therefore
                // must model the invalidation normally performed by application
                // command services before asserting the persisted read model.
                baselineService.markDirty();
                ReviewQualityBaseline after = baselineService.loadBaseline();

                assertThat(after.totalFindings() - before.totalFindings()).isEqualTo(6);
                assertThat(after.highRiskFindings() - before.highRiskFindings()).isEqualTo(5);
                assertThat(after.labeledHighRiskFindings() - before.labeledHighRiskFindings()).isEqualTo(4);
                assertThat(after.confirmedHighRiskFindings() - before.confirmedHighRiskFindings()).isEqualTo(3);
                assertThat(after.falsePositiveHighRiskFindings() - before.falsePositiveHighRiskFindings()).isOne();
                assertThat(after.anchoredFindings() - before.anchoredFindings()).isEqualTo(5);
                assertThat(after.duplicateFindings() - before.duplicateFindings()).isOne();
                assertThat(after.completedTasks() - before.completedTasks()).isEqualTo(4);
                assertThat(after.totalLlmEstimatedCost().subtract(before.totalLlmEstimatedCost()))
                    .isEqualByComparingTo("0.423400");
                assertThat(after.groups())
                    .filteredOn(group -> ruleId.equals(group.ruleId())
                        && repository.equals(group.repository())
                        && "HIGH".equals(group.severity()))
                    .singleElement()
                    .satisfies(group -> {
                        assertThat(group.source()).isEqualTo("RULE");
                        assertThat(group.language()).isEqualTo("JAVA");
                        assertThat(group.totalFindings()).isEqualTo(5);
                        assertThat(group.confirmedValidCount()).isEqualTo(3);
                        assertThat(group.falsePositiveCount()).isOne();
                        assertThat(group.pendingCount()).isOne();
                        assertThat(group.anchoredCount()).isEqualTo(4);
                        assertThat(group.labeledPrecision()).isEqualByComparingTo("75.00");
                    });

                Summary calibrationSummary = calibrationQueueMapper.selectVersionSummary(
                    ruleId,
                    "legacy-detector-v1",
                    1,
                    "review-prompt-v2",
                    "review-context-v2",
                    "review-schema-v2",
                    "high-risk-verifier-v1",
                    "server-risk-v2"
                );
                assertThat(calibrationSummary).isNotNull();
                assertThat(calibrationSummary.totalFindings()).isEqualTo(5);
                assertThat(calibrationSummary.labeledCount()).isEqualTo(4);
                assertThat(calibrationSummary.confirmedValidCount()).isEqualTo(3);
                assertThat(calibrationSummary.falsePositiveCount()).isOne();
                assertThat(calibrationSummary.pendingCount()).isOne();
                assertThat(calibrationSummary.anchoredCount()).isEqualTo(4);
                assertThat(calibrationSummary.duplicateCount()).isOne();

                List<Sample> calibrationSamples = calibrationQueueMapper.selectPendingSamples(
                    ruleId,
                    "legacy-detector-v1",
                    1,
                    "review-prompt-v2",
                    "review-context-v2",
                    "review-schema-v2",
                    "high-risk-verifier-v1",
                    "server-risk-v2",
                    true,
                    30
                );
                Long calibrationTaskId = taskId;
                assertThat(calibrationSamples)
                    .singleElement()
                    .satisfies(sample -> {
                        assertThat(sample.taskId()).isEqualTo(calibrationTaskId);
                        assertThat(sample.feedbackStatus()).isEqualTo("IGNORED");
                        assertThat(sample.message()).isEqualTo("ignored");
                    });
            } finally {
                for (Long insertedTaskId : taskIds) {
                    jdbcTemplate.update("delete from review_finding where task_id = ?", insertedTaskId);
                }
                jdbcTemplate.update("delete from review_task where organization = ?", organization);
                if (!taskIds.isEmpty()) {
                    baselineService.markDirty();
                    baselineService.refreshIfDirty();
                }
            }
        }
    }

    @Test
    void terminalStateAndNotificationOutboxCommitOrRollBackAtomically() throws Exception {
        RabbitTopology topology = RabbitTopology.unique("atomic");
        try (ConfigurableApplicationContext context = start("api", topology.arguments())) {
            JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);
            ReviewTaskMapper reviewTaskMapper = context.getBean(ReviewTaskMapper.class);
            ReviewTaskClaimService claimService = context.getBean(ReviewTaskClaimService.class);
            NotificationDispatchService notificationDispatchService =
                context.getBean(NotificationDispatchService.class);
            TransactionTemplate transactionTemplate = new TransactionTemplate(
                context.getBean(PlatformTransactionManager.class)
            );
            RabbitTemplate rabbitTemplate = context.getBean(RabbitTemplate.class);
            String organization = "outbox-atomic-" + Long.toUnsignedString(System.nanoTime());
            Long taskId = null;

            try {
                taskId = insertReviewTask(
                    jdbcTemplate,
                    organization,
                    9201,
                    "REVIEWING",
                    false,
                    "NOT_REQUIRED"
                );
                claimReviewTask(jdbcTemplate, taskId, "atomic-claim");
                Long rolledBackTaskId = taskId;

                assertThatThrownBy(() -> transactionTemplate.executeWithoutResult(status -> {
                    ReviewTask task = completedTask(reviewTaskMapper.selectById(rolledBackTaskId));
                    assertThat(claimService.writeTerminalStateIfClaimOwned(task, "atomic-claim")).isTrue();
                    notificationDispatchService.reviewFinished(task, 0);
                    throw new IllegalStateException("force rollback");
                }))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessage("force rollback");

                assertThat(jdbcTemplate.queryForObject(
                    "select status from review_task where id = ?",
                    String.class,
                    taskId
                )).isEqualTo("REVIEWING");
                assertThat(notificationEventCount(jdbcTemplate, taskId)).isZero();

                Long committedTaskId = taskId;
                transactionTemplate.executeWithoutResult(status -> {
                    ReviewTask task = completedTask(reviewTaskMapper.selectById(committedTaskId));
                    assertThat(claimService.writeTerminalStateIfClaimOwned(task, "atomic-claim")).isTrue();
                    notificationDispatchService.reviewFinished(task, 0);
                });

                assertThat(jdbcTemplate.queryForObject(
                    "select status from review_task where id = ?",
                    String.class,
                    taskId
                )).isEqualTo("COMPLETED");
                assertThat(notificationEventCount(jdbcTemplate, taskId)).isOne();
                Long eventId = jdbcTemplate.queryForObject(
                    "select id from notification_event where task_id = ?",
                    Long.class,
                    taskId
                );
                awaitExecutorIdle(context, "notificationPublishWorkerExecutor");
                String publishStatus = jdbcTemplate.queryForObject(
                    "select status from notification_event where id = ?",
                    String.class,
                    eventId
                );
                assertThat(publishStatus).isIn(
                    "PENDING",
                    "PUBLISHING",
                    "PUBLISHED",
                    "PUBLISH_FAILED"
                );
                if ("PUBLISHED".equals(publishStatus)) {
                    assertThat(rabbitTemplate.receive(topology.queue(), 5000)).isNotNull();
                }
            } finally {
                if (taskId != null) {
                    jdbcTemplate.update(
                        "delete from notification_delivery_log where event_id in "
                            + "(select id from notification_event where task_id = ?)",
                        taskId
                    );
                    jdbcTemplate.update("delete from notification_event where task_id = ?", taskId);
                }
                jdbcTemplate.update("delete from review_task where organization = ?", organization);
                deleteRabbitTopology(context, topology);
            }
        }
    }

    @Test
    void notificationOutboxRecoversAfterRealRabbitRoutingFailure() throws Exception {
        RabbitTopology topology = RabbitTopology.unique("recovery");
        try (ConfigurableApplicationContext context = start("worker", topology.arguments())) {
            JdbcTemplate jdbcTemplate = context.getBean(JdbcTemplate.class);
            RabbitAdmin rabbitAdmin = context.getBean(RabbitAdmin.class);
            RabbitTemplate rabbitTemplate = context.getBean(RabbitTemplate.class);
            NotificationEventPublishCompensator compensator =
                context.getBean(NotificationEventPublishCompensator.class);
            String eventKey = "INTEGRATION_RABBIT_RECOVERY:" + Long.toUnsignedString(System.nanoTime());
            Long eventId = null;

            try {
                assertThat(rabbitAdmin.deleteExchange(topology.exchange())).isTrue();
                LocalDateTime now = LocalDateTime.now();
                jdbcTemplate.update("""
                    insert into notification_event (
                        event_key, event_type, task_id, batch_id, payload, status,
                        retry_count, next_retry_at, last_error, created_at, updated_at
                    ) values (?, 'REVIEW_FAILED', ?, null, ?, 'PENDING', 0, ?, null, ?, ?)
                    """,
                    eventKey,
                    990000001L,
                    "{\"eventType\":\"REVIEW_FAILED\",\"taskId\":990000001}",
                    now.minusSeconds(1),
                    now,
                    now
                );
                eventId = jdbcTemplate.queryForObject(
                    "select id from notification_event where event_key = ?",
                    Long.class,
                    eventKey
                );

                compensator.compensate();
                awaitNotificationStatus(jdbcTemplate, eventId, "PUBLISH_FAILED");

                rabbitAdmin.declareExchange(
                    context.getBean("notificationExchange", DirectExchange.class)
                );
                rabbitAdmin.declareBinding(
                    context.getBean("notificationBinding", Binding.class)
                );
                jdbcTemplate.update(
                    "update notification_event set next_retry_at = ? where id = ?",
                    LocalDateTime.now().minusSeconds(1),
                    eventId
                );

                compensator.compensate();
                awaitNotificationStatus(jdbcTemplate, eventId, "PUBLISHED");
                assertThat(rabbitTemplate.receive(topology.queue(), 5000)).isNotNull();
            } finally {
                if (eventId != null) {
                    jdbcTemplate.update(
                        "delete from notification_delivery_log where event_id = ?",
                        eventId
                    );
                    jdbcTemplate.update("delete from notification_event where id = ?", eventId);
                }
                deleteRabbitTopology(context, topology);
            }
        }
    }

    private ConfigurableApplicationContext start(String runtimeRole, String... additionalArguments) {
        List<String> arguments = new ArrayList<>(List.of(
            "--app.runtime.role=" + runtimeRole,
            "--app.github.webhook.enabled=false",
            "--app.security.admin-api-key.enabled=false",
            "--app.cors.allowed-origins[0]=https://integration.local",
            "--server.port=0",
            "--spring.main.banner-mode=off",
            "--spring.task.scheduling.enabled=false"
        ));
        if (Arrays.stream(additionalArguments)
            .noneMatch(argument -> argument.startsWith("--app.runtime.api.instance-count="))) {
            arguments.add("--app.runtime.api.instance-count=" + ("worker".equals(runtimeRole) ? 0 : 1));
        }
        arguments.addAll(Arrays.asList(additionalArguments));
        return new SpringApplicationBuilder(RepoGuardApplication.class)
            .web(WebApplicationType.SERVLET)
            .profiles("prod")
            .run(arguments.toArray(String[]::new));
    }

    private Long insertAuthAccount(
        JdbcTemplate jdbcTemplate,
        String username,
        String email,
        String passwordHash,
        String status,
        int sessionVersion,
        LocalDateTime now
    ) {
        jdbcTemplate.update("""
            insert into user_account (
                username, email, password_hash, role, status, failed_login_count,
                locked_until, session_version, last_login_at, created_at, updated_at
            ) values (?, ?, ?, 'VIEWER', ?, 0, null, ?, null, ?, ?)
            """,
            username,
            email,
            passwordHash,
            status,
            sessionVersion,
            now,
            now
        );
        return jdbcTemplate.queryForObject(
            "select id from user_account where username = ?",
            Long.class,
            username
        );
    }

    private void insertRefreshToken(
        JdbcTemplate jdbcTemplate,
        Long userId,
        AuthTokenService.TokenIssue refreshToken,
        AuthTokenService authTokenService
    ) {
        LocalDateTime now = LocalDateTime.now();
        jdbcTemplate.update("""
            insert into user_refresh_token (
                user_id, token_hash, session_version, status, expires_at,
                revoked_at, last_used_at, created_at, updated_at
            ) values (?, ?, 1, 'ACTIVE', ?, null, null, ?, ?)
            """,
            userId,
            authTokenService.hashRefreshToken(refreshToken.token()),
            now.plusSeconds(refreshToken.expiresInSeconds()),
            now,
            now
        );
    }

    private void assertApiInstanceAllows(AuthTokenFilter filter, String token) throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(200);
    }

    private void assertApiInstanceRejects(AuthTokenFilter filter, String token) throws ServletException, IOException {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/auth/me");
        request.addHeader("Authorization", "Bearer " + token);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertThat(response.getStatus()).isEqualTo(401);
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

    private void insertQualityFinding(
        JdbcTemplate jdbcTemplate,
        Long taskId,
        String ruleId,
        String severity,
        Integer lineNumber,
        String feedbackStatus,
        String message
    ) {
        jdbcTemplate.update("""
            insert into review_finding (
                task_id, category, severity, source, rule_id, file_path,
                line_number, message, recommendation, feedback_status, anchor_type
            ) values (?, 'FINDING', ?, 'RULE', ?, 'src/main/java/example/Quality.java',
                      ?, ?, 'quality baseline recommendation', ?, ?)
            """,
            taskId,
            severity,
            ruleId,
            lineNumber,
            message,
            feedbackStatus,
            lineNumber == null ? "NONE" : "ADDED_LINE"
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

    private void claimReviewTask(JdbcTemplate jdbcTemplate, Long taskId, String claimId) {
        LocalDateTime claimedAt = LocalDateTime.now().minusMinutes(1);
        jdbcTemplate.update("""
            update review_task
            set started_at = ?, review_claimed_at = ?, review_claimed_by = ?
            where id = ?
            """, claimedAt, claimedAt, claimId, taskId);
    }

    private ReviewTask completedTask(ReviewTask task) {
        task.setStatus("COMPLETED");
        task.setRiskLevel("LOW");
        task.setLlmStatus("COMPLETED");
        task.setLlmProvider("integration");
        task.setLlmModel("integration-model");
        task.setFinishedAt(LocalDateTime.now());
        task.setDurationSeconds(60);
        return task;
    }

    private int notificationEventCount(JdbcTemplate jdbcTemplate, Long taskId) {
        return jdbcTemplate.queryForObject(
            "select count(*) from notification_event where task_id = ?",
            Integer.class,
            taskId
        );
    }

    private void awaitNotificationStatus(
        JdbcTemplate jdbcTemplate,
        Long eventId,
        String expectedStatus
    ) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        String actual = null;
        while (System.nanoTime() < deadline) {
            actual = jdbcTemplate.queryForObject(
                "select status from notification_event where id = ?",
                String.class,
                eventId
            );
            if (expectedStatus.equals(actual)) {
                return;
            }
            Thread.sleep(50);
        }
        assertThat(actual).isEqualTo(expectedStatus);
    }

    private void awaitExecutorIdle(
        ConfigurableApplicationContext context,
        String beanName
    ) throws InterruptedException {
        ThreadPoolExecutor executor = context.getBean(beanName, ThreadPoolExecutor.class);
        Thread.sleep(100);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (executor.getActiveCount() == 0 && executor.getQueue().isEmpty()) {
                return;
            }
            Thread.sleep(25);
        }
        assertThat(executor.getActiveCount()).isZero();
        assertThat(executor.getQueue()).isEmpty();
    }

    private void deleteRabbitTopology(
        ConfigurableApplicationContext context,
        RabbitTopology topology
    ) {
        RabbitAdmin rabbitAdmin = context.getBean(RabbitAdmin.class);
        rabbitAdmin.deleteQueue(topology.queue());
        rabbitAdmin.deleteQueue(topology.deadLetterQueue());
        rabbitAdmin.deleteExchange(topology.exchange());
        rabbitAdmin.deleteExchange(topology.deadLetterExchange());
    }

    private RateLimitCapacitySample measureRateLimitCapacity(
        List<DatabaseRateLimitWindowStore> stores,
        String scope,
        long windowEpochMinute,
        int targetRps
    ) {
        long intervalNanos = TimeUnit.SECONDS.toNanos(1) / targetRps;
        long nextStartNanos = System.nanoTime();
        List<Long> durations = new ArrayList<>(targetRps);
        int allowedRequests = 0;
        for (int index = 0; index < targetRps; index++) {
            if (index > 0) {
                LockSupport.parkNanos(Math.max(0L, nextStartNanos - System.nanoTime()));
            }
            long startedAt = System.nanoTime();
            DatabaseRateLimitWindowStore store = stores.get(index % stores.size());
            if (store.tryAcquire(scope, "same-client", windowEpochMinute, targetRps)) {
                allowedRequests++;
            }
            durations.add(System.nanoTime() - startedAt);
            nextStartNanos += intervalNanos;
        }
        durations.sort(Long::compareTo);
        return new RateLimitCapacitySample(
            stores.size(),
            targetRps,
            allowedRequests,
            percentile(durations, 0.95d),
            percentile(durations, 0.99d)
        );
    }

    private long percentile(List<Long> sortedValues, double percentile) {
        int index = Math.max(0, (int) Math.ceil(sortedValues.size() * percentile) - 1);
        return sortedValues.get(index);
    }

    private double nanosToMillis(long nanos) {
        return nanos / 1_000_000.0d;
    }

    private double databaseFailureCount(MeterRegistry meterRegistry) {
        return meterRegistry.find("repoguard.security.shared_rate_limit.database.failures")
            .counters()
            .stream()
            .mapToDouble(counter -> counter.count())
            .sum();
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

    private record RateLimitCapacitySample(
        int instanceCount,
        int targetRps,
        int allowedRequests,
        long p95Nanos,
        long p99Nanos
    ) {
    }

    private record RabbitTopology(
        String exchange,
        String queue,
        String routingKey,
        String deadLetterExchange,
        String deadLetterQueue,
        String deadLetterRoutingKey
    ) {

        static RabbitTopology unique(String purpose) {
            String suffix = purpose + "." + Long.toUnsignedString(System.nanoTime());
            return new RabbitTopology(
                "repoguard.it.notification.exchange." + suffix,
                "repoguard.it.notification.queue." + suffix,
                "repoguard.it.notification.created." + suffix,
                "repoguard.it.notification.dlx." + suffix,
                "repoguard.it.notification.dlq." + suffix,
                "repoguard.it.notification.dead." + suffix
            );
        }

        String[] arguments() {
            return new String[] {
                "--app.rabbit.notification.exchange=" + exchange,
                "--app.rabbit.notification.queue=" + queue,
                "--app.rabbit.notification.routing-key=" + routingKey,
                "--app.rabbit.notification.dead-letter-exchange=" + deadLetterExchange,
                "--app.rabbit.notification.dead-letter-queue=" + deadLetterQueue,
                "--app.rabbit.notification.dead-letter-routing-key=" + deadLetterRoutingKey,
                "--spring.rabbitmq.listener.simple.auto-startup=false"
            };
        }
    }
}
