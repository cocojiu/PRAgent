package com.repoguard.agent.integration;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.spring.MybatisSqlSessionFactoryBean;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.MybatisPlusConfig;
import com.repoguard.agent.config.ReviewWorkflowProperties;
import com.repoguard.agent.dto.*;
import com.repoguard.agent.mapper.*;
import com.repoguard.agent.notification.workflow.ReviewMemberAssignmentService;
import com.repoguard.agent.notification.workflow.ReviewWorkflowServiceImpl;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import com.repoguard.agent.review.task.ReviewTaskTransitionStore;
import com.repoguard.agent.service.ReviewTaskCommandService;
import com.repoguard.agent.service.ReviewWorkflowService;
import com.repoguard.agent.tenancy.TenantContext;
import com.repoguard.agent.tenancy.TenantProperties;
import java.sql.Connection;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.*;
import javax.sql.DataSource;
import org.apache.ibatis.session.SqlSessionFactory;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionTemplate;

/** Opt-in fixture on a dedicated local instance; never accepts a production host or database. */
@EnabledIfEnvironmentVariable(named = "REPOGUARD_ASSIGNMENT_MYSQL_URL", matches = "jdbc:mysql://127\\.0\\.0\\.1:33080/repoguard_assignment_test(?:[?].*)?")
@SuppressWarnings("try")
class ReviewMemberAssignmentMysqlIntegrationTest {
    private static AnnotationConfigApplicationContext context;
    private static DataSource dataSource;
    private static JdbcTemplate jdbc;
    private static ReviewMemberAssignmentService service;
    private static ReviewWorkflowService workflow;
    private Fixture fixture;
    private record Fixture(long tenant, long otherTenant, long task, long attempt, long first, long second, long foreignMember) { }

    @BeforeAll static void open() {
        dataSource = new DriverManagerDataSource(System.getenv("REPOGUARD_ASSIGNMENT_MYSQL_URL"), "root", "");
        jdbc = new JdbcTemplate(dataSource);
        var identity = jdbc.queryForMap("select @@port as port, @@datadir as datadir, @@bind_address as bindAddress, database() as db");
        assertThat(((Number) identity.get("port")).intValue()).isEqualTo(33080);
        assertThat(identity.get("bindAddress")).isEqualTo("127.0.0.1");
        assertThat(identity.get("db")).isEqualTo("repoguard_assignment_test");
        assertThat(identity.get("datadir").toString().replace('\\', '/')).endsWith("/.tmp/mysql-assignment-20261008/data/");
        assertThat(jdbc.queryForObject("select version from flyway_schema_history where success=1 order by installed_rank desc limit 1", String.class)).isEqualTo("103");
        context = new AnnotationConfigApplicationContext();
        context.registerBean(DataSource.class, () -> dataSource); context.register(FixtureConfiguration.class); context.refresh();
        service = context.getBean(ReviewMemberAssignmentService.class); workflow = context.getBean(ReviewWorkflowService.class);
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(service)).isTrue();
        assertThat(org.springframework.aop.support.AopUtils.isAopProxy(workflow)).isTrue();
    }
    @AfterAll static void close() { if (context != null) context.close(); }
    @BeforeEach void seed() {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        long tenant = insert("insert into tenant(tenant_key,display_name,status) values (?, 'Assignment fixture', 'ACTIVE')", "assignment-" + suffix);
        long other = insert("insert into tenant(tenant_key,display_name,status) values (?, 'Other fixture', 'ACTIVE')", "other-" + suffix);
        long first = member(tenant, "member-a-" + suffix, "REVIEWER", "ACTIVE");
        long second = member(tenant, "member-b-" + suffix, "REVIEWER", "ACTIVE");
        member(tenant, "read-only-" + suffix, "READ_ONLY", "ACTIVE");
        member(tenant, "disabled-" + suffix, "REVIEWER", "DISABLED");
        long foreign = member(other, "foreign-" + suffix, "REVIEWER", "ACTIVE");
        long task = insert("""
            insert into review_task(tenant_id,pr_number,title,repository,organization,commit_sha,branch_name,
                status,risk_level,mq_retries,publish_attempts,llm_status,source,trigger_source,human_review_required,
                human_review_status,created_at,duration_seconds,generation)
            values (?,1,'Assignment fixture','fixture-repo','fixture-owner',?,'fixture','PENDING_HUMAN_REVIEW','HIGH',
                0,0,'NOT_REQUIRED','MANUAL_INPUT','MANUAL_INPUT',1,'PENDING',now(),0,1)
            """, tenant, "a".repeat(40));
        long attempt = insert("""
            insert into review_execution_attempt(tenant_id,task_id,attempt_no,generation,commit_sha,input_fingerprint,status,
                queued_at,started_at,created_at) values (?,?,1,1,?,?,'COMPLETED',now(),now(),now())
            """, tenant, task, "a".repeat(40), "b".repeat(64));
        assertThat(jdbc.update("update review_task set current_attempt_id=? where id=? and tenant_id=?", attempt, task, tenant)).isEqualTo(1);
        fixture = new Fixture(tenant, other, task, attempt, first, second, foreign);
    }
    private static long insert(String sql, Object... values) {
        var keys = new GeneratedKeyHolder();
        assertThat(jdbc.update(connection -> {
            var statement = connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS);
            for (int i = 0; i < values.length; i++) statement.setObject(i + 1, values[i]);
            return statement;
        }, keys)).isEqualTo(1);
        return keys.getKey().longValue();
    }
    private static long member(long tenant, String username, String role, String status) {
        long id = insert("insert into user_account(username,email,password_hash,role,status,created_at,updated_at) values (?,?,'fixture-unusable-for-login','REVIEWER',?,now(),now())",
            username, username + "@fixture.invalid", status);
        insert("insert into tenant_membership(tenant_id,user_id,role) values (?,?,?)", tenant, id, role);
        return id;
    }
    private ReviewMemberAssignmentRequest selection(long user) {
        try (var scope = TenantContext.withTenant(fixture.tenant())) {
            var options = service.options(fixture.task(), null);
            return new ReviewMemberAssignmentRequest(user, options.attemptId(), options.headSha(), options.assignmentVersion());
        }
    }
    private Object confirm(ReviewMemberAssignmentRequest selection) {
        try (var scope = TenantContext.withTenant(fixture.tenant())) { return service.confirm(fixture.task(), selection, "fixture-operator"); }
        catch (BusinessException ex) { return ex.getErrorCode(); }
    }
    private String assignee() { return jdbc.queryForObject("select review_assignee from review_task where id=?", String.class, fixture.task()); }
    @Test void actualMapperProjectsOnlyEligibleTenantMembersAndLiteralPrefix() {
        try (var scope = TenantContext.withTenant(fixture.tenant())) {
            var options = service.options(fixture.task(), null);
            assertThat(options.members()).extracting(ReviewAssignmentOptionsResponse.Member::userId).containsExactly(fixture.first(), fixture.second());
            assertThat(service.options(fixture.task(), "%").members()).isEmpty();
            assertThat(service.options(fixture.task(), "_").members()).isEmpty();
            assertThat(service.options(fixture.task(), "member-b-").members()).extracting(ReviewAssignmentOptionsResponse.Member::userId).containsExactly(fixture.second());
        }
        try (var scope = TenantContext.withTenant(fixture.otherTenant())) {
            assertThatThrownBy(() -> service.options(fixture.task(), null)).isInstanceOf(BusinessException.class);
        }
        assertThat(confirm(selection(fixture.foreignMember()))).isEqualTo(ErrorCode.CONFLICT); assertThat(assignee()).isNull();
    }
    @Test void writesCanonicalAssigneeAndDefaultSlaUsingRealTransactionProxies() {
        var result = confirm(selection(fixture.first())); assertThat(result).isInstanceOf(ReviewMemberAssignmentResponse.class);
        assertThat(assignee()).isEqualTo(jdbc.queryForObject("select username from user_account where id=?", String.class, fixture.first()));
        assertThat(jdbc.queryForObject("select timestampdiff(MINUTE,review_assigned_at,review_sla_deadline) from review_task where id=?", Integer.class, fixture.task())).isEqualTo(120);
    }
    @Test void rejectsAnOldOptionsVersionAfterARealManualReassignment() {
        var selected = selection(fixture.first());
        String second = jdbc.queryForObject("select username from user_account where id=?", String.class, fixture.second());
        try (var scope = TenantContext.withTenant(fixture.tenant())) { workflow.assign(fixture.task(), new ReviewAssignmentRequest(second, null), "fixture"); }
        assertThat(confirm(selected)).isEqualTo(ErrorCode.CONFLICT); assertThat(assignee()).isEqualTo(second);
    }
    @Test void waitsForUncommittedMembershipRevocationThenRejectsAfterCommit() throws Exception {
        var selected = selection(fixture.first());
        try (Connection revoker = dataSource.getConnection(); var executor = Executors.newFixedThreadPool(1)) {
            revoker.setAutoCommit(false);
            try (var change = revoker.prepareStatement("update tenant_membership set role='READ_ONLY' where tenant_id=? and user_id=?")) {
                change.setLong(1, fixture.tenant()); change.setLong(2, fixture.first()); assertThat(change.executeUpdate()).isEqualTo(1);
            }
            var future = executor.submit(() -> confirm(selected));
            try { awaitLockWaits(1); assertThat(future.isDone()).isFalse(); revoker.commit(); }
            finally { revoker.rollback(); }
            assertThat(future.get(10, TimeUnit.SECONDS)).isEqualTo(ErrorCode.CONFLICT); assertThat(assignee()).isNull();
        }
    }
    @Test void exactlyOneConcurrentConfirmationCanCommitTheSameAssignmentVersion() throws Exception {
        var first = selection(fixture.first()); var second = new ReviewMemberAssignmentRequest(fixture.second(), first.attemptId(), first.headSha(), first.assignmentVersion());
        try (Connection blocker = dataSource.getConnection(); var executor = Executors.newFixedThreadPool(2)) {
            blocker.setAutoCommit(false);
            try (var lock = blocker.prepareStatement("select id from review_task where id=? for update")) {
                lock.setLong(1, fixture.task()); try (var row = lock.executeQuery()) { assertThat(row.next()).isTrue(); }
            }
            var a = executor.submit(() -> confirm(first)); var b = executor.submit(() -> confirm(second));
            try { awaitLockWaits(2); assertThat(a.isDone()).isFalse(); assertThat(b.isDone()).isFalse(); blocker.commit(); }
            finally { blocker.rollback(); }
            var results = java.util.List.of(a.get(10, TimeUnit.SECONDS), b.get(10, TimeUnit.SECONDS));
            assertThat(results.stream().filter(ReviewMemberAssignmentResponse.class::isInstance).count()).isEqualTo(1);
            assertThat(results.stream().filter(ErrorCode.CONFLICT::equals).count()).isEqualTo(1); assertThat(assignee()).isNotBlank();
        }
    }
    @Test void rollsBackAnAssignmentWhenTheOuterTransactionAborts() {
        var selected = selection(fixture.first());
        var transaction = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        assertThatThrownBy(() -> transaction.execute(status -> {
            try (var scope = TenantContext.withTenant(fixture.tenant())) { service.confirm(fixture.task(), selected, "fixture"); }
            throw new IllegalStateException("fixture rollback");
        })).isInstanceOf(IllegalStateException.class).hasMessage("fixture rollback");
        assertThat(assignee()).isNull();
    }
    @Test void recommendationUsesActualMysqlCurrentFindingsRenameDedupAndFreshMembership(@org.junit.jupiter.api.io.TempDir java.nio.file.Path directory) throws Exception {
        insert("insert into changed_file(tenant_id,task_id,attempt_id,file_path,change_type) values (?,?,?,'new/A.java','RENAME')",
            fixture.tenant(), fixture.task(), fixture.attempt());
        for (String category : java.util.List.of("FINDING", "FINDING", "MISSING_TEST")) {
            insert("insert into review_finding(tenant_id,task_id,attempt_id,file_path,category,severity) values (?,?,?,'new/A.java',?,'HIGH')",
                fixture.tenant(), fixture.task(), fixture.attempt(), category);
        }
        insert("insert into review_finding(tenant_id,task_id,attempt_id,file_path,category,severity,current_attempt) values (?,?,?,'new/A.java','FINDING','CRITICAL',0)",
            fixture.tenant(), fixture.task(), fixture.attempt());
        var mapper = new com.repoguard.agent.config.JacksonConfig().objectMapper();
        var mapping = directory.resolve("owners.json");
        java.nio.file.Files.writeString(mapping, mapper.writeValueAsString(java.util.List.of(java.util.Map.of(
            "tenantId", fixture.tenant(), "repository", "fixture-owner/fixture-repo",
            "owners", java.util.Map.of("@owner", java.util.List.of(fixture.first(), fixture.foreignMember()))))));
        var github = mock(com.repoguard.agent.github.GithubIntegrationProvider.class);
        var heads = mock(com.repoguard.agent.github.GithubPullRequestHeadReader.class);
        var contents = mock(com.repoguard.agent.github.GithubChangedFileContentReader.class);
        var paths = mock(com.repoguard.agent.github.GithubPullRequestPathsReader.class);
        var resilience = mock(com.repoguard.agent.external.ExternalCallResilience.class);
        var settings = new com.repoguard.agent.github.GithubIntegrationSettings("GITHUB", "CONFIGURED", "https://api.github.com", null, null, "fixture-owner", "fixture-repo", 1L);
        when(github.getSettingsForRepository("fixture-owner", "fixture-repo")).thenReturn(settings);
        when(heads.fetchCodeowners(any(), anyString(), anyString(), anyString(), anyInt(), anyString(), any(), any()))
            .thenReturn(new com.repoguard.agent.github.GithubPullRequestHeadReader.CodeownersSource("c".repeat(40), "CODEOWNERS", "* @owner", "AVAILABLE"));
        when(paths.fetch(any(), anyString(), anyString(), anyString(), anyInt(), anyString(), anyString(), any()))
            .thenReturn(new com.repoguard.agent.github.GithubPullRequestPathsReader.Paths("COMPLETE", java.util.Map.of("new/A.java",
                new com.repoguard.agent.github.GithubPullRequestPathsReader.Change("new/A.java", "renamed", "old/A.java"))));
        var query = new com.repoguard.agent.notification.workflow.CodeownersQueryService(true, mapping, mapper,
            context.getBean(ReviewTaskMapper.class), jdbc, github, heads, contents, resilience, paths);
        try (var scope = TenantContext.withTenant(fixture.tenant())) {
            var result = query.query(fixture.task()); assertThat(result.status()).isEqualTo("RECOMMENDATIONS");
            assertThat(result.recommendations().candidates()).hasSize(1);
            var candidate = result.recommendations().candidates().getFirst();
            assertThat(candidate.userId()).isEqualTo(fixture.first()); assertThat(candidate.coveredFiles()).isEqualTo(1);
            assertThat(candidate.findingCount()).isEqualTo(2); assertThat(candidate.riskScore()).isEqualTo(4);
            assertThat(candidate.paths()).containsExactly("new/A.java", "old/A.java");
            assertThat(jdbc.update("update tenant_membership set role='READ_ONLY' where tenant_id=? and user_id=?", fixture.tenant(), fixture.first())).isEqualTo(1);
            assertThat(query.query(fixture.task()).status()).isEqualTo("UNASSIGNED");
        }
        try (var scope = TenantContext.withTenant(fixture.otherTenant())) { assertThat(query.query(fixture.task()).status()).isEqualTo("NOT_FOUND"); }
    }
    private static void awaitLockWaits(int minimum) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < end) {
            var count = jdbc.queryForObject("""
                select count(*) from performance_schema.data_lock_waits w
                join performance_schema.data_locks l on l.engine_lock_id=w.requesting_engine_lock_id
                where l.object_schema='repoguard_assignment_test'
                """, Integer.class);
            if (count >= minimum) return;
            Thread.sleep(20);
        }
        fail("Expected actual InnoDB lock wait was not observed");
    }
    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class FixtureConfiguration {
        @Bean PlatformTransactionManager transactionManager(DataSource ds) { return new DataSourceTransactionManager(ds); }
        @Bean SqlSessionFactory sqlSessionFactory(DataSource ds) throws Exception {
            var configuration = new MybatisConfiguration(); configuration.setMapUnderscoreToCamelCase(true);
            configuration.addMapper(ReviewTaskMapper.class); configuration.addMapper(TenantMembershipMapper.class);
            var properties = new TenantProperties(); properties.setEnabled(true);
            var factory = new MybatisSqlSessionFactoryBean(); factory.setDataSource(ds); factory.setConfiguration(configuration);
            factory.setPlugins(new MybatisPlusConfig().mybatisPlusInterceptor(properties)); return factory.getObject();
        }
        @Bean SqlSessionTemplate sqlSessionTemplate(SqlSessionFactory factory) { return new SqlSessionTemplate(factory); }
        @Bean ReviewTaskMapper reviewTaskMapper(SqlSessionTemplate template) { return template.getMapper(ReviewTaskMapper.class); }
        @Bean TenantMembershipMapper tenantMembershipMapper(SqlSessionTemplate template) { return template.getMapper(TenantMembershipMapper.class); }
        @Bean ReviewTaskTransitionStore transitions(ReviewTaskMapper tasks) { return new ReviewTaskTransitionStore(tasks, new ReviewTaskStateMachine()); }
        @Bean ReviewWorkflowService workflow(ReviewTaskMapper tasks, TenantMembershipMapper members, ReviewTaskTransitionStore transitions) {
            return new ReviewWorkflowServiceImpl(tasks, mock(NotificationReadStateMapper.class), mock(ReviewBotCommandAuditMapper.class),
                mock(ReviewTaskCommandService.class), transitions, new ReviewWorkflowProperties(), members);
        }
        @Bean ReviewMemberAssignmentService assignment(ReviewTaskMapper tasks, TenantMembershipMapper members, ReviewWorkflowService workflow) {
            return new ReviewMemberAssignmentService(tasks, members, workflow);
        }
    }
}
