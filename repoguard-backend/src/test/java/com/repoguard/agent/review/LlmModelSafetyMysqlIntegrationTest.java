package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.dto.LlmModelRollbackRequest;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.external.ExternalHttpJsonResponseReader;
import com.repoguard.agent.external.ExternalHttpResponseReader;
import com.repoguard.agent.review.quality.LlmModelReleaseRepository;
import com.repoguard.agent.review.quality.LlmModelSafetyService;
import com.repoguard.agent.review.quality.LlmModelReleaseService;
import com.repoguard.agent.review.quality.LlmQualityComparisonProvider;
import com.repoguard.agent.config.JacksonConfig;
import com.repoguard.agent.tenancy.TenantContext;
import com.sun.net.httpserver.HttpServer;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.RestClient;

/** Real database plus local HTTP verifies the gate, including revocation during an in-flight call. */
@EnabledIfEnvironmentVariable(named = "REPOGUARD_RUN_INTEGRATION_TESTS", matches = "true")
class LlmModelSafetyMysqlIntegrationTest {
    private static DriverManagerDataSource data;
    private static JdbcTemplate jdbc;
    private long tenant;
    private long releaseId;
    private LlmUsageBudgetLedger ledger;
    private LlmModelReleaseRepository releases;
    private LlmModelSafetyService safety;
    private HttpServer server;
    private final AtomicInteger sent = new AtomicInteger();
    private final CountDownLatch received = new CountDownLatch(1);
    private final CountDownLatch respond = new CountDownLatch(1);
    private boolean holdResponse;

    @BeforeAll
    static void migrate() {
        data = new DriverManagerDataSource(System.getenv("SPRING_DATASOURCE_URL"),
            System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "root"),
            System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", ""));
        jdbc = new JdbcTemplate(data);
        Flyway.configure().dataSource(data).cleanDisabled(true)
            .initSql("SET SESSION sql_mode = REPLACE(@@SESSION.sql_mode, 'ONLY_FULL_GROUP_BY', '')")
            .locations("classpath:db/migration").load().migrate();
    }

    @BeforeEach
    void fixture() throws Exception {
        String key = "safety-" + UUID.randomUUID();
        jdbc.update("insert into tenant(tenant_key,display_name) values(?,?)", key, "Safety fixture");
        tenant = jdbc.queryForObject("select id from tenant where tenant_key=?", Long.class, key);
        jdbc.update("""
            insert into llm_model_release(tenant_id,release_key,provider,model_name,prompt_version,context_version,
                schema_version,dataset_id,dataset_version,dataset_fingerprint,state,traffic_percent)
            values(?,'safety-fixture','fixture','fixture','p1','c1','s1','fixture','v1',?,'ACTIVE',100)
            """, tenant, "a".repeat(64));
        releaseId = jdbc.queryForObject("select id from llm_model_release where tenant_id=? and release_key='safety-fixture'", Long.class, tenant);
        ObjectMapper json = new ObjectMapper();
        ledger = new LlmUsageBudgetLedger(jdbc, new DataSourceTransactionManager(data), json);
        releases = new LlmModelReleaseRepository(jdbc, json);
        safety = new LlmModelSafetyService(jdbc, releases, json);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/chat/completions", exchange -> {
            exchange.getRequestBody().readAllBytes(); sent.incrementAndGet(); received.countDown();
            if (holdResponse) {
                try { if (!respond.await(5, TimeUnit.SECONDS)) throw new java.io.IOException("fixture timeout"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new java.io.IOException(interrupted); }
            }
            byte[] response = "{\"choices\":[{\"message\":{\"content\":\"{}\"}}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":10,\"total_tokens\":110}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
    }

    @AfterEach
    void cleanup() {
        respond.countDown(); if (server != null) server.stop(0);
        if (tenant == 0) return;
        for (String table : new String[] {"llm_usage_reconciliation", "llm_usage_call", "llm_usage_month",
            "llm_model_release_audit", "llm_model_release", "tenant"}) {
            jdbc.update("delete from " + table + " where " + (table.equals("tenant") ? "id" : "tenant_id") + "=?", tenant);
        }
    }

    @Test
    void ordinaryRollbackStillSendsButSafetyRevocationStopsThePinnedVersion() {
        try (TenantContext.Scope _ = TenantContext.withTenant(tenant)) {
            releases.rollback(tenant, releaseId, "ordinary rollback fixture");
            var transport = transport();
            call(transport, "rollback-allowed");
            var revoked = revoke();
            assertThat(revoked.safetyRevoked()).isTrue();
            assertThat(revoked.state()).isEqualTo("ROLLED_BACK");
            assertThat(revoked.releaseKey()).isEqualTo("safety-fixture");
            assertThatThrownBy(() -> call(transport, "safety-denied")).hasMessageContaining("llm_release_safety_revoked");
            assertThat(sent).hasValue(1);
            assertThat(jdbc.queryForObject("select sum(accounted_tokens) from llm_usage_month where tenant_id=?", Long.class, tenant)).isEqualTo(110);
            assertThat(jdbc.queryForObject("select accounted_tokens from llm_usage_call where tenant_id=? and call_key='safety-denied'", Long.class, tenant)).isZero();
            assertThat(jdbc.queryForObject("select count(*) from llm_model_release_audit where tenant_id=? and action='SAFETY_REVOKE'", Integer.class, tenant)).isEqualTo(1);
            revoke();
            assertThat(jdbc.queryForObject("select count(*) from llm_model_release_audit where tenant_id=?", Integer.class, tenant)).isEqualTo(1);
        }
    }

    @Test
    void inFlightCallKeepsItsUsageWhileSubsequentCallsAreRejected() throws Exception {
        holdResponse = true;
        try (var workers = Executors.newFixedThreadPool(1)) {
            var first = workers.submit(() -> {
                try (TenantContext.Scope _ = TenantContext.withTenant(tenant)) { return call(transport(), "in-flight"); }
            });
            try {
                assertThat(received.await(5, TimeUnit.SECONDS)).isTrue();
                try (TenantContext.Scope _ = TenantContext.withTenant(tenant)) {
                    revoke();
                    assertThatThrownBy(() -> call(transport(), "queued-after-revoke")).hasMessageContaining("llm_release_safety_revoked");
                }
                assertThat(sent).hasValue(1);
            } finally { respond.countDown(); }
            assertThat(first.get(5, TimeUnit.SECONDS)).isNotNull();
        }
        assertThat(jdbc.queryForObject("select accounted_tokens from llm_usage_call where tenant_id=? and call_key='in-flight'", Long.class, tenant)).isEqualTo(110);
        assertThat(jdbc.queryForObject("select status from llm_usage_call where tenant_id=? and call_key='in-flight'", String.class, tenant)).isEqualTo("SETTLED");
    }

    @Test
    void repeatedCenterReadsLeaveUnsafeReleaseAndAuditUnchanged() {
        jdbc.update("update llm_model_release set state='CANARY', traffic_percent=25 where tenant_id=? and id=?", tenant, releaseId);
        var service = releaseService(releases);
        try (TenantContext.Scope _ = TenantContext.withTenant(tenant)) {
            assertThat(service.getCenter(7).canaryRelease().state()).isEqualTo("CANARY");
            assertThat(service.getCenter(7).canaryRelease().state()).isEqualTo("CANARY");
        }
        assertThat(releases.findById(tenant, releaseId).state()).isEqualTo("CANARY");
        assertThat(jdbc.queryForObject("select count(*) from llm_model_release_audit where tenant_id=?", Integer.class, tenant)).isZero();
    }

    @Test
    void concurrentCoordinationCommitsOneRollbackAndOneAudit() throws Exception {
        jdbc.update("update llm_model_release set state='CANARY', traffic_percent=25 where tenant_id=? and id=?", tenant, releaseId);
        var service = releaseService(releases);
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<Void> coordinate = () -> {
                try (TenantContext.Scope _ = TenantContext.withTenant(tenant)) {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("coordination fixture timeout");
                    service.reconcileCanaries();
                    return null;
                }
            };
            var first = workers.submit(coordinate);
            var second = workers.submit(coordinate);
            try { assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); }
            finally { start.countDown(); }
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertThat(releases.findById(tenant, releaseId).state()).isEqualTo("ROLLED_BACK");
        assertThat(jdbc.queryForObject("select count(*) from llm_model_release_audit where tenant_id=? and action='AUTO_ROLLBACK'", Integer.class, tenant)).isEqualTo(1);
    }

    @Test
    void auditFailureRollsBackTheReleaseTransition() {
        jdbc.update("update llm_model_release set state='CANARY', traffic_percent=25 where tenant_id=? and id=?", tenant, releaseId);
        var failing = org.mockito.Mockito.spy(releases);
        org.mockito.Mockito.doThrow(new IllegalStateException("fixture audit failure")).when(failing).insertAudit(
            org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
        try (TenantContext.Scope _ = TenantContext.withTenant(tenant)) {
            assertThatThrownBy(() -> releaseService(failing).reconcileCanaries()).hasMessageContaining("fixture audit failure");
        }
        assertThat(releases.findById(tenant, releaseId).state()).isEqualTo("CANARY");
        assertThat(jdbc.queryForObject("select count(*) from llm_model_release_audit where tenant_id=?", Integer.class, tenant)).isZero();
    }

    @Test
    void automaticRollbackCannotChangeAnActiveReleaseOrAnotherTenant() {
        assertThat(releases.rollbackCanary(tenant + 1, releaseId, "fixture stale tenant")).isZero();
        assertThat(releases.rollbackCanary(tenant, releaseId, "fixture stale canary")).isZero();
        assertThat(releases.findById(tenant, releaseId).state()).isEqualTo("ACTIVE");
    }

    @Test
    void routingDoesNotAssignACanaryRolledBackAfterItsFirstRead() throws Exception {
        jdbc.update("update llm_model_release set state='CANARY', traffic_percent=100 where tenant_id=? and id=?", tenant, releaseId);
        var concurrent = org.mockito.Mockito.spy(releases);
        try (var workers = Executors.newFixedThreadPool(1)) {
            org.mockito.Mockito.doAnswer(invocation -> {
                assertThat(workers.submit(() -> releases.rollbackCanary(tenant, releaseId, "fixture concurrent metrics"))
                    .get(5, TimeUnit.SECONDS)).isEqualTo(1);
                return invocation.callRealMethod();
            }).when(concurrent).findByState(tenant, "CANARY");
            var task = new ReviewTask();
            var settings = new ReviewPolicySettings(true, true, "fixture", "configured", "http://127.0.0.1", "",
                10, BigDecimal.ZERO, 100, true, 1, 5, 100, 5, 100, null, null, ReviewStrategyRelease.observeDefaults(), null);
            try (TenantContext.Scope _ = TenantContext.withTenant(tenant)) {
                assertThat(releaseService(concurrent).route(settings, task).modelName()).isEqualTo("configured");
            }
            assertThat(task.getLlmReleaseKey()).isNull();
        }
    }

    @Test
    void metricCollectionExcludesConcurrentReleaseCommands() throws Exception {
        var snapshots = org.mockito.Mockito.mock(com.repoguard.agent.review.quality.LlmModelReleaseMetricsRepository.class);
        org.mockito.Mockito.when(snapshots.upsert(org.mockito.ArgumentMatchers.any())).thenAnswer(call -> call.getArgument(0));
        try (var workers = Executors.newFixedThreadPool(1);
             var context = new org.springframework.context.annotation.AnnotationConfigApplicationContext()) {
            context.registerBean(JdbcTemplate.class, () -> jdbc);
            context.registerBean(ObjectMapper.class, () -> new JacksonConfig().objectMapper());
            context.registerBean(LlmModelReleaseRepository.class, () -> releases);
            context.registerBean(com.repoguard.agent.review.quality.LlmModelReleaseMetricsRepository.class, () -> snapshots);
            context.registerBean(com.repoguard.agent.observability.RepoGuardMetrics.class,
                () -> org.mockito.Mockito.mock(com.repoguard.agent.observability.RepoGuardMetrics.class));
            context.registerBean(com.repoguard.agent.review.quality.LlmModelReleaseAlertPublisher.class,
                () -> org.mockito.Mockito.mock(com.repoguard.agent.review.quality.LlmModelReleaseAlertPublisher.class));
            context.register(Class.forName("com.repoguard.agent.review.quality.LlmModelReleaseRuntimeSupport"),
                com.repoguard.agent.review.quality.LlmModelReleaseMetricsService.class);
            context.refresh();
            org.mockito.Mockito.when(snapshots.aggregate(org.mockito.ArgumentMatchers.eq(tenant),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
                .thenAnswer(call -> {
                    int lockResult = workers.submit(() -> {
                        try {
                            new TransactionTemplate(new DataSourceTransactionManager(data)).execute(status ->
                                jdbc.queryForObject("select id from tenant where id=? for update nowait", Long.class, tenant));
                            return 0;
                        } catch (org.springframework.dao.DataAccessException failure) {
                            Throwable cause = org.springframework.core.NestedExceptionUtils.getMostSpecificCause(failure);
                            if (cause instanceof java.sql.SQLException sql) return sql.getErrorCode();
                            throw failure;
                        }
                    }).get(5, TimeUnit.SECONDS);
                    assertThat(lockResult).as("competing release command must encounter the held tenant lock").isEqualTo(3572);
                    return new com.repoguard.agent.review.quality.LlmModelReleaseMetricsRepository.RuntimeAggregate(
                        0L, 0L, BigDecimal.ZERO, 0L, 0L, 0L);
                });
            var service = (com.repoguard.agent.review.quality.LlmModelReleaseMetricsService) transactionalProxy(
                context.getBean(com.repoguard.agent.review.quality.LlmModelReleaseMetricsService.class));
            try (TenantContext.Scope _ = TenantContext.withTenant(tenant)) {
                assertThat(service.collectCurrentWindow()).hasSize(1);
            }
        }
    }

    private LlmModelReleaseService releaseService(LlmModelReleaseRepository repository) {
        var target = new LlmModelReleaseService(jdbc, org.mockito.Mockito.mock(LlmQualityComparisonProvider.class),
            repository, new JacksonConfig().objectMapper());
        return (LlmModelReleaseService) transactionalProxy(target);
    }

    private Object transactionalProxy(Object target) {
        var transaction = new org.springframework.transaction.interceptor.TransactionInterceptor();
        transaction.setTransactionManager(new DataSourceTransactionManager(data));
        transaction.setTransactionAttributeSource(new org.springframework.transaction.annotation.AnnotationTransactionAttributeSource());
        var factory = new org.springframework.aop.framework.ProxyFactory(target);
        factory.addAdvice(transaction);
        return factory.getProxy();
    }

    private com.repoguard.agent.dto.LlmModelReleaseDto revoke() {
        return new TransactionTemplate(new DataSourceTransactionManager(data)).execute(status ->
            safety.revoke(releaseId, new LlmModelRollbackRequest("verified fixture incident"), "fixture-admin"));
    }

    private com.fasterxml.jackson.databind.JsonNode call(LlmChatTransport transport, String key) {
        var task = new ReviewTask(); task.setLlmReleaseKey("safety-fixture");
        var settings = new ReviewPolicySettings(true, true, "fixture", "fixture",
            "http://127.0.0.1:" + server.getAddress().getPort(), "", 10, BigDecimal.ZERO, 100, true,
            1, 5, 100, 5, 100, new BigDecimal("2"), new BigDecimal("8"), ReviewStrategyRelease.observeDefaults(), null);
        Map<String, Object> payload = Map.of("model", "fixture", "max_tokens", 100,
            "messages", List.of(Map.of("role", "user", "content", "fixture")));
        return transport.execute(null, settings, "", "fixture", 100, payload,
            new LlmProviderCapability("fixture", LlmStructuredOutputMode.NONE), task, key, "review");
    }

    private LlmChatTransport transport() {
        ObjectMapper json = new ObjectMapper();
        return new LlmChatTransport(RestClient.builder(), new ExternalHttpJsonResponseReader(json, new ExternalHttpResponseReader()),
            new LlmChatCompletionResponseExtractor(json), ledger);
    }
}
