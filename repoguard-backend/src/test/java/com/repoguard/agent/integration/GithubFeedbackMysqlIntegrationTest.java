package com.repoguard.agent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.repoguard.agent.cache.CacheEvictionService;
import com.repoguard.agent.config.MybatisPlusConfig;
import com.repoguard.agent.config.ReviewHumanReviewProperties;
import com.repoguard.agent.dto.FindingFeedbackRequest;
import com.repoguard.agent.github.webhook.GithubFeedbackEventStore;
import com.repoguard.agent.github.webhook.GithubFeedbackService;
import com.repoguard.agent.github.webhook.GithubFeedbackVerifier;
import com.repoguard.agent.github.webhook.GithubWebhookProperties;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.review.ReviewFindingRiskRecalibrator;
import com.repoguard.agent.review.HumanReviewPolicyEvaluator;
import com.repoguard.agent.review.RiskLevelRanker;
import com.repoguard.agent.review.ServerRiskAggregator;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import com.repoguard.agent.review.task.ReviewTaskTransitionStore;
import com.repoguard.agent.service.impl.FindingFeedbackServiceImpl;
import com.repoguard.agent.tenancy.TenantContext;
import com.repoguard.agent.tenancy.TenantProperties;
import com.repoguard.agent.tenancy.TenantRepositoryBinding;
import com.repoguard.agent.tenancy.TenantRepositoryResolver;
import com.repoguard.agent.timeline.ReviewTimelineAppender;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import java.util.TimeZone;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.api.parallel.Resources;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.support.TransactionTemplate;

/** Real MySQL feedback/risk/timeline transactions; remote verification and repository binding are isolated fixtures. */
@EnabledIfEnvironmentVariable(named = "REPOGUARD_RUN_INTEGRATION_TESTS", matches = "true")
@ResourceLock(Resources.TIME_ZONE)
class GithubFeedbackMysqlIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String OWNER = "fixture-org";
    private static final String REPOSITORY = "fixture-repo";
    private static final String SHA = "a".repeat(40);
    private static JdbcTemplate jdbc;
    private static SqlSessionTemplate session;
    private static DataSourceTransactionManager transactions;
    private static TimeZone previousTimeZone;
    private long tenant;
    private long otherTenant;
    private long task;
    private long finding;
    private LocalDateTime firstTime;
    private GithubFeedbackEventStore store;
    private GithubFeedbackVerifier verifier;
    private TenantRepositoryResolver bindings;
    private FindingFeedbackServiceImpl feedback;
    private GithubFeedbackService service;

    @BeforeAll
    static void open() throws Exception {
        // CI connects with serverTimezone=UTC. Exercise a different JVM zone explicitly.
        previousTimeZone = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"));
        var data = new DriverManagerDataSource(System.getenv("SPRING_DATASOURCE_URL"),
            System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "root"),
            System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", ""));
        Flyway.configure().dataSource(data).cleanDisabled(true)
            .initSql("SET SESSION sql_mode = REPLACE(@@SESSION.sql_mode, 'ONLY_FULL_GROUP_BY', '')")
            .locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(data);
        transactions = new DataSourceTransactionManager(data);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        for (Class<?> mapper : List.of(ReviewTaskMapper.class, ReviewFindingMapper.class, ReviewTimelineMapper.class)) {
            configuration.addMapper(mapper);
        }
        var tenancy = new TenantProperties(); tenancy.setEnabled(true);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(data); factory.setConfiguration(configuration);
        factory.setPlugins(new MybatisPlusConfig().mybatisPlusInterceptor(tenancy));
        session = new SqlSessionTemplate(factory.getObject());
    }

    @AfterAll
    static void restoreTimeZone() {
        if (previousTimeZone != null) TimeZone.setDefault(previousTimeZone);
    }

    @BeforeEach
    void seed() {
        firstTime = LocalDateTime.now().minusMinutes(5).withNano(0);
        tenant = insert("insert into tenant(tenant_key,display_name) values(?,?)", UUID.randomUUID().toString(), "feedback replay");
        otherTenant = insert("insert into tenant(tenant_key,display_name) values(?,?)", UUID.randomUUID().toString(), "other replay");
        task = insert("""
            insert into review_task(tenant_id,pr_number,title,repository,organization,commit_sha,branch_name,
                status,risk_level,assessment_status,llm_status,created_at)
            values(?,1,'feedback fixture',?,?,?,'main','COMPLETED','HIGH','COMPLETE','COMPLETED',?)
            """, tenant, REPOSITORY, OWNER, SHA, firstTime.minusMinutes(1));
        long attempt = attempt(1);
        jdbc.update("update review_task set current_attempt_id=? where id=?", attempt, task);
        finding = insert("""
            insert into review_finding(tenant_id,task_id,attempt_id,category,current_attempt,file_path,severity,source,
                confidence,is_blocking,enforcement_mode)
            values(?,?,?,'FINDING',1,'src/Fixture.java','HIGH','LLM','HIGH',0,'COMMENT')
            """, tenant, task, attempt);
        insert("""
            insert into github_comment_publication(tenant_id,task_id,finding_id,target_type,status,success,
                github_comment_id,published_at,created_at,updated_at) values(?,?,?,'line','published',1,3001,?,?,?)
            """, tenant, task, finding, firstTime.minusSeconds(30), firstTime.minusSeconds(30), firstTime.minusSeconds(30));
        var tenancy = new TenantProperties(); tenancy.setEnabled(true);
        store = spy(new GithubFeedbackEventStore(jdbc, tenancy));
        bindings = mock(TenantRepositoryResolver.class);
        when(bindings.resolve(OWNER, REPOSITORY, null))
            .thenReturn(new TenantRepositoryBinding(tenant, "fixture", OWNER, REPOSITORY, null));
        verifier = mock(GithubFeedbackVerifier.class);
        var risk = new ReviewFindingRiskRecalibrator(session.getMapper(ReviewFindingMapper.class),
            new ServerRiskAggregator(), new HumanReviewPolicyEvaluator(new RiskLevelRanker(), new ReviewHumanReviewProperties()));
        feedback = new FindingFeedbackServiceImpl(session.getMapper(ReviewTaskMapper.class),
            session.getMapper(ReviewFindingMapper.class), new ReviewTimelineAppender(session.getMapper(ReviewTimelineMapper.class)),
            mock(CacheEvictionService.class), risk,
            new ReviewTaskTransitionStore(session.getMapper(ReviewTaskMapper.class), new ReviewTaskStateMachine()));
        var properties = new GithubWebhookProperties(); properties.setFeedbackEnabled(true);
        properties.setAllowedRepositories(List.of(OWNER + "/" + REPOSITORY));
        service = new GithubFeedbackService(properties, bindings, store, verifier, feedback, transactions, new SimpleMeterRegistry());
    }

    @AfterEach
    void cleanup() {
        for (long id : new long[] {tenant, otherTenant}) {
            if (id == 0) continue;
            for (String table : List.of("github_feedback_event", "github_comment_publication", "review_timeline", "review_finding",
                "review_execution_attempt", "review_task", "tenant")) {
                jdbc.update("delete from " + table + " where " + (table.equals("tenant") ? "id" : "tenant_id") + "=?", id);
            }
        }
    }

    @Test
    void laterQueuedDecisionWinsInOccurrenceOrder() {
        receive(4001, "false-positive earlier reason", firstTime);
        receive(4002, "ignore later reason", firstTime.plusMinutes(1));
        process(tenant);
        assertThat(status()).isEqualTo("IGNORED");
        assertThat(jdbc.queryForObject("select feedback_note from review_finding where id=?", String.class, finding)).isEqualTo("later reason");
        assertThat(feedbackAt()).isEqualTo(firstTime.plusMinutes(1));
        assertThat(eventStatuses()).containsExactly("APPLIED", "APPLIED");
        assertThat(timelineRows()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select risk_level from review_task where id=?", String.class, task)).isEqualTo("MEDIUM");
    }

    @Test
    void olderOutOfOrderDecisionCannotReplaceAYoungerEvent() {
        receive(4002, "ignore later reason", firstTime.plusMinutes(1));
        receive(4001, "false-positive earlier reason", firstTime);
        process(tenant);
        assertThat(status()).isEqualTo("IGNORED");
        assertThat(eventStatuses()).containsExactly("APPLIED", "IGNORED");
        assertThat(failureCode(4001)).isEqualTo("NEWER_FEEDBACK_EXISTS");
        assertThat(timelineRows()).isEqualTo(1);
    }

    @Test
    void newerManualFeedbackKeepsLocalOperationTimeAndRejectsQueuedEvents() {
        receive(4001, "false-positive earlier reason", firstTime);
        LocalDateTime before = LocalDateTime.now().withNano(0);
        try (var _ = TenantContext.withTenant(tenant)) {
            new TransactionTemplate(transactions).executeWithoutResult(tx ->
                feedback.updateFindingFeedback(task, finding, new FindingFeedbackRequest("valid", "manual correction"), "manual-reviewer"));
        }
        assertThat(feedbackAt()).isBetween(before, LocalDateTime.now());
        process(tenant);
        assertThat(status()).isEqualTo("VALID");
        assertThat(eventStatuses()).containsExactly("IGNORED");
        assertThat(failureCode(4001)).isEqualTo("NEWER_FEEDBACK_EXISTS");
        assertThat(timelineRows()).isEqualTo(1);
    }

    @Test
    void concurrentRedeliveriesCreateAndApplyOnlyOneEvent() throws Exception {
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> { start.await(); return service.receive(payload(4001, "false-positive reason", firstTime), "delivery-a"); });
            var second = workers.submit(() -> { start.await(); return service.receive(payload(4001, "false-positive reason", firstTime), "delivery-b"); });
            start.countDown();
            assertThat(List.of(first.get(10, TimeUnit.SECONDS).existing(), second.get(10, TimeUnit.SECONDS).existing()))
                .containsExactlyInAnyOrder(true, false);
        }
        assertThat(eventStatuses()).containsExactly("PENDING");
        process(tenant); process(tenant);
        assertThat(eventStatuses()).containsExactly("APPLIED");
        assertThat(timelineRows()).isEqualTo(1);
        verify(verifier, times(1)).rejection(any());
    }

    @Test
    void failedEventCompletionRollsBackFindingTimelineAndRiskBeforeRetry() {
        receive(4001, "false-positive reason", firstTime);
        doThrow(new IllegalStateException("fixture completion failure")).when(store).finish(anyLong(), eq("APPLIED"), isNull());
        process(tenant);
        assertThat(status()).isEqualTo("UNREVIEWED"); assertThat(feedbackAt()).isNull();
        assertThat(timelineRows()).isZero(); assertThat(eventStatuses()).containsExactly("PENDING");
        assertThat(jdbc.queryForObject("select risk_level from review_task where id=?", String.class, task)).isEqualTo("HIGH");
        assertThat(jdbc.queryForObject("select attempts from github_feedback_event where tenant_id=?", Integer.class, tenant)).isEqualTo(1);
        doCallRealMethod().when(store).finish(anyLong(), eq("APPLIED"), isNull());
        jdbc.update("update github_feedback_event set next_attempt_at=now() where tenant_id=?", tenant);
        process(tenant);
        assertThat(eventStatuses()).containsExactly("APPLIED"); assertThat(timelineRows()).isEqualTo(1);
        assertThat(feedbackAt()).isEqualTo(firstTime);
        assertThat(jdbc.queryForObject("select risk_level from review_task where id=?", String.class, task)).isEqualTo("INFO");
    }

    @Test
    void concurrentProcessorsApplyTheLockedEventOnlyOnce() throws Exception {
        receive(4001, "false-positive reason", firstTime);
        var verified = new CountDownLatch(2);
        when(verifier.rejection(any())).thenAnswer(call -> {
            verified.countDown();
            assertThat(verified.await(10, TimeUnit.SECONDS)).isTrue();
            return null;
        });
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> process(tenant));
            var second = workers.submit(() -> process(tenant));
            first.get(15, TimeUnit.SECONDS); second.get(15, TimeUnit.SECONDS);
        }
        assertThat(eventStatuses()).containsExactly("APPLIED");
        assertThat(timelineRows()).isEqualTo(1);
        assertThat(feedbackAt()).isEqualTo(firstTime);
    }

    @Test
    void otherTenantCannotDrainEventsAndStaleAttemptCannotReceiveFeedback() {
        receive(4001, "false-positive reason", firstTime);
        process(otherTenant);
        assertThat(eventStatuses()).containsExactly("PENDING");
        long newerAttempt = attempt(2);
        jdbc.update("update review_task set current_attempt_id=? where id=?", newerAttempt, task);
        jdbc.update("update review_finding set current_attempt=0 where id=?", finding);
        process(tenant);
        assertThat(status()).isEqualTo("UNREVIEWED"); assertThat(eventStatuses()).containsExactly("IGNORED");
        assertThat(failureCode(4001)).isEqualTo("STALE_PUBLICATION"); assertThat(timelineRows()).isZero();
    }

    @Test
    void repositoryReboundToAnotherTenantCannotQueueAnExistingPublication() {
        when(bindings.resolve(OWNER, REPOSITORY, null))
            .thenReturn(new TenantRepositoryBinding(otherTenant, "other", OWNER, REPOSITORY, null));
        assertThat(service.receive(payload(4001, "false-positive reason", firstTime), "foreign-delivery").status()).isEqualTo("skipped");
        assertThat(eventStatuses()).isEmpty(); assertThat(status()).isEqualTo("UNREVIEWED");
    }

    private void receive(long comment, String command, LocalDateTime at) {
        assertThat(service.receive(payload(comment, command, at), "delivery-" + comment).status()).isEqualTo("accepted");
    }

    private void process(long tenantId) {
        try (var _ = TenantContext.withTenant(tenantId)) { service.processDue(); }
    }

    private ObjectNode payload(long commentId, String command, LocalDateTime at) {
        ObjectNode root = JSON.createObjectNode(); root.put("action", "created");
        root.putObject("repository").put("name", REPOSITORY).putObject("owner").put("login", OWNER);
        root.putObject("pull_request").put("number", 1).putObject("head").put("sha", SHA);
        root.putObject("sender").put("id", 1001);
        var comment = root.putObject("comment"); comment.put("id", commentId).put("in_reply_to_id", 3001)
            .put("body", "/repoguard " + command).put("commit_id", SHA)
            .put("created_at", at.atZone(ZoneId.systemDefault()).toOffsetDateTime().toString());
        comment.putObject("user").put("id", 1001).put("login", "fixture-reviewer").put("type", "User");
        return root;
    }

    private long attempt(int number) {
        return insert("""
            insert into review_execution_attempt(tenant_id,task_id,attempt_no,generation,commit_sha,input_fingerprint,
                status,queued_at,started_at,created_at) values(?,?,?,1,?,?,'COMPLETED',now(),now(),now())
            """, tenant, task, number, SHA, UUID.randomUUID().toString().replace("-", "").repeat(2));
    }

    private String status() { return jdbc.queryForObject("select feedback_status from review_finding where id=?", String.class, finding); }
    private LocalDateTime feedbackAt() { return jdbc.queryForObject("select feedback_at from review_finding where id=?", LocalDateTime.class, finding); }
    private int timelineRows() { return jdbc.queryForObject("select count(*) from review_timeline where tenant_id=? and task_id=?", Integer.class, tenant, task); }
    private List<String> eventStatuses() { return jdbc.queryForList("select status from github_feedback_event where tenant_id=? order by id", String.class, tenant); }
    private String failureCode(long comment) { return jdbc.queryForObject("select failure_code from github_feedback_event where tenant_id=? and comment_id=?", String.class, tenant, comment); }

    private static long insert(String sql, Object... values) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int index = 0; index < values.length; index++) statement.setObject(index + 1, values[index]);
            return statement;
        }, keys);
        return java.util.Objects.requireNonNull(keys.getKey()).longValue();
    }
}
