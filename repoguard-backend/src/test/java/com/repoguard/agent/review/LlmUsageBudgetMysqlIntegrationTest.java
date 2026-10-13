package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

/** Independent JDBC connections exercise durable admission, rather than a mocked lock. */
@EnabledIfEnvironmentVariable(named = "REPOGUARD_RUN_INTEGRATION_TESTS", matches = "true")
class LlmUsageBudgetMysqlIntegrationTest {
    private static final Clock OCTOBER = Clock.fixed(Instant.parse("2026-10-31T23:59:59Z"), ZoneOffset.UTC);
    private static DriverManagerDataSource data;
    private JdbcTemplate jdbc;
    private long tenant;
    private LlmUsageBudgetLedger ledger;

    @BeforeAll
    static void migrate() {
        data = new DriverManagerDataSource(System.getenv("SPRING_DATASOURCE_URL"),
            System.getenv().getOrDefault("SPRING_DATASOURCE_USERNAME", "root"),
            System.getenv().getOrDefault("SPRING_DATASOURCE_PASSWORD", ""));
        Flyway.configure().dataSource(data).cleanDisabled(true)
            .initSql("SET SESSION sql_mode = REPLACE(@@SESSION.sql_mode, 'ONLY_FULL_GROUP_BY', '')")
            .locations("classpath:db/migration").load().migrate();
    }

    @BeforeEach
    void fixture() {
        jdbc = new JdbcTemplate(data);
        String key = "budget-" + UUID.randomUUID();
        jdbc.update("insert into tenant(tenant_key,display_name,status) values(?,?,'ACTIVE')", key, "Budget fixture");
        tenant = jdbc.queryForObject("select id from tenant where tenant_key=?", Long.class, key);
        ledger = ledger(OCTOBER);
        jdbc.update("insert into tenant_quota_config(tenant_id,monthly_llm_token_budget,monthly_llm_cost_budget) values(?,1124,0)", tenant);
    }

    @AfterEach
    void removeOnlyFixture() {
        if (tenant == 0) return;
        for (String table : new String[] {"llm_usage_reconciliation", "llm_usage_call", "llm_usage_month", "tenant_quota_config", "tenant"}) {
            jdbc.update("delete from " + table + " where " + (table.equals("tenant") ? "id" : "tenant_id") + "=?", tenant);
        }
    }

    @Test
    void twoWorkersCanSendOnlyOneCallWhenOnlyOneReservationFits() throws Exception {
        var ready = new CountDownLatch(2);
        var begin = new CountDownLatch(1);
        var rejected = new CountDownLatch(1);
        var finishRequest = new CountDownLatch(1);
        var sent = new AtomicInteger();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> compete("first", ready, begin, rejected, finishRequest, sent));
            var second = workers.submit(() -> compete("second", ready, begin, rejected, finishRequest, sent));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue(); begin.countDown();
            try {
                assertThat(rejected.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(sent).hasValue(1);
                assertThat(tokens()).isEqualTo(1124);
            } finally {
                finishRequest.countDown();
            }
            assertThat(first.get(5, TimeUnit.SECONDS) + second.get(5, TimeUnit.SECONDS)).isEqualTo(1);
        }
        assertThat(tokens()).isEqualTo(110);
        assertThat(jdbc.queryForObject("select sum(accounted_tokens) from llm_usage_call where tenant_id=?", Long.class, tenant))
            .isEqualTo(tokens());
    }

    private int compete(String key, CountDownLatch ready, CountDownLatch begin, CountDownLatch rejected,
        CountDownLatch finish, AtomicInteger sent) {
        try (TenantContext.Scope _ = TenantContext.withTenant(tenant)) {
            ready.countDown(); await(begin);
            try {
                ledger.execute(null, key, "review", settings(), "", "", 100, () -> {
                    sent.incrementAndGet(); await(finish); return "response";
                }, response -> new LlmCallResult("", 100, 10, 110));
                return 1;
            } catch (LlmUsageBudgetLedger.AdmissionDeniedException denied) {
                assertThat(denied.getCategory()).isEqualTo("llm_monthly_budget_exhausted");
                rejected.countDown(); return 0;
            }
        }
    }

    @Test
    void processLossKeepsReservationAndCrossMonthSettlementUsesOriginalMonth() {
        try (TenantContext.Scope _ = TenantContext.withTenant(tenant)) {
            var reserved = ledger.reserve(null, "crash", "review", settings(), "", "", 100);
            var restarted = ledger(Clock.fixed(Instant.parse("2026-11-01T00:00:01Z"), ZoneOffset.UTC));
            assertThat(restarted.pendingUsage(100)).hasSize(1);
            restarted.settle(reserved, settings(), new LlmCallResult("", 100, 10, 110));
            restarted.settle(reserved, settings(), new LlmCallResult("", 999, 999, 1998));
            assertThat(tokens()).isEqualTo(110);
            assertThat(jdbc.queryForObject("select count(*) from llm_usage_month where tenant_id=? and usage_month='2026-11-01'", Integer.class, tenant))
                .isZero();
            assertThat(restarted.pendingUsage(100)).isEmpty();
            restarted.reserve(null, "next-month", "review", settings(), "", "", 100);
            assertThat(jdbc.queryForObject("select accounted_tokens from llm_usage_month where tenant_id=? and usage_month='2026-11-01'", Long.class, tenant))
                .isEqualTo(1124);
        }
    }

    @Test
    void uncertaintyAndDuplicateKeysNeverMakeTheReservedBudgetFree() {
        try (TenantContext.Scope _ = TenantContext.withTenant(tenant)) {
            assertThatThrownBy(() -> ledger.execute(null, "failed", "review", settings(), "", "", 100,
                () -> { throw new IllegalStateException("fixture uncertain timeout"); }, result -> null))
                .hasMessage("fixture uncertain timeout");
            assertThat(tokens()).isEqualTo(1124);
            assertThat(ledger.pendingUsage(1).getFirst()).containsEntry("status", "UNKNOWN");
            jdbc.update("update tenant_quota_config set monthly_llm_token_budget=10000 where tenant_id=?", tenant);
            assertThatThrownBy(() -> ledger.reserve(null, "failed", "review", settings(), "", "", 100))
                .hasMessageContaining("llm_budget_duplicate_call");
            assertThat(tokens()).isEqualTo(1124);
        }
    }

    @Test
    void unexpectedProviderUsageIsAccountedAndPreventsFurtherAdmission() {
        try (TenantContext.Scope _ = TenantContext.withTenant(tenant)) {
            ledger.execute(null, "overshoot", "review", settings(), "", "", 100,
                () -> "response", result -> new LlmCallResult("", 2000, 10, 2010));
            assertThat(tokens()).isEqualTo(2010);
            assertThatThrownBy(() -> ledger.reserve(null, "future", "review", settings(), "", "", 100))
                .hasMessageContaining("llm_monthly_budget_exhausted");
        }
    }

    @Test
    void finiteCostCannotAdmitUnknownPricingAndSchemaBytesAreReserved() {
        try (TenantContext.Scope _ = TenantContext.withTenant(tenant)) {
            jdbc.update("update tenant_quota_config set monthly_llm_token_budget=0,monthly_llm_cost_budget=1 where tenant_id=?", tenant);
            var configured = settings();
            var unknown = new ReviewPolicySettings(true, true, "fixture", "fixture", configured.baseUrl(), "",
                10, BigDecimal.ZERO, 100, true, 1, 5, 100, 5, 100, BigDecimal.ZERO, BigDecimal.ZERO,
                configured.strategyRelease(), null);
            assertThatThrownBy(() -> ledger.reserve(null, "unpriced", "review", unknown, "", "", 100))
                .hasMessageContaining("llm_budget_pricing_unknown");
            ledger.execute(null, "schema", "review", configured, "", "", 100,
                java.util.Map.of("response_format", "x".repeat(5000)), () -> "no usage", result -> null);
            assertThat(tokens()).isGreaterThan(6000);
        }
    }

    private long tokens() {
        return jdbc.queryForObject("select accounted_tokens from llm_usage_month where tenant_id=? and usage_month='2026-10-01'", Long.class, tenant);
    }

    @Test
    void knownNotSentDeadlineReleasesBothBudgetsAndRecordsTheReason() {
        try (TenantContext.Scope _ = TenantContext.withTenant(tenant)) {
            var evaluation = new LlmEvaluationBudget(10000, BigDecimal.TEN);
            assertThatThrownBy(() -> evaluation.execute("", "", 100, new BigDecimal("2"), new BigDecimal("8"),
                () -> ledger.execute(null, "not-sent", "review", settings(), "", "", 100,
                    () -> { throw new LlmUsageBudgetLedger.AdmissionDeniedException("llm_budget_deadline_exhausted"); },
                    result -> null), result -> null)).hasMessageContaining("llm_budget_deadline_exhausted");
            assertThat(tokens()).isZero();
            assertThat(evaluation.accountedTokens()).isZero();
            assertThat(evaluation.accountedCost()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(jdbc.queryForObject("select json_unquote(json_extract(usage_json,'$.requestSent')) from llm_usage_call where tenant_id=? and call_key='not-sent'", String.class, tenant))
                .isEqualTo("false");
        }
    }

    private LlmUsageBudgetLedger ledger(Clock clock) {
        return new LlmUsageBudgetLedger(jdbc, new DataSourceTransactionManager(data), new ObjectMapper(), clock);
    }

    private static ReviewPolicySettings settings() { return LlmUsageBudgetMathTest.settings(); }

    @Test
    void pendingDiagnosticsPreserveUtcWallTimeAcrossDatasourceTimezones() {
        jdbc.update("update tenant_quota_config set monthly_llm_token_budget=10000 where tenant_id=?", tenant);
        try (TenantContext.Scope _ = TenantContext.withTenant(tenant)) {
            for (String timezone : new String[] {"UTC", "Asia/Shanghai"}) {
                var zoned = new DriverManagerDataSource(System.getenv("SPRING_DATASOURCE_URL") + "&connectionTimeZone=" + timezone,
                    data.getUsername(), data.getPassword());
                var zonedLedger = new LlmUsageBudgetLedger(new JdbcTemplate(zoned),
                    new DataSourceTransactionManager(zoned), new ObjectMapper(), OCTOBER);
                String key = "clock-" + timezone.replace('/', '-');
                zonedLedger.reserve(null, key, "review", settings(), "", "", 100);
                var pending = zonedLedger.pendingCalls(100).stream().filter(row -> row.callKey().equals(key)).findFirst().orElseThrow();
                assertThat(pending.month()).isEqualTo(java.time.LocalDate.of(2026, 10, 1));
                assertThat(pending.startedAt()).isEqualTo(OCTOBER.instant().atOffset(ZoneOffset.UTC));
            }
        }
    }

    @Test
    void finalOperatorEvidenceIsAuditedIdempotentAndTenantScoped() {
        try (TenantContext.Scope _ = TenantContext.withTenant(tenant)) {
            assertThatThrownBy(() -> ledger.execute(null, "uncertain", "review", settings(), "", "", 100,
                () -> { throw new IllegalStateException("fixture lost response"); }, result -> null))
                .hasMessage("fixture lost response");
            var request = new com.repoguard.agent.dto.LlmUsageAccountingDto.ReconciliationRequest(
                "uncertain", "invoice-1", 123, new BigDecimal("0.003"), "fixture-invoice-item-1", "Final provider usage", true);
            assertThat(ledger.pendingCalls(100)).hasSize(1);
            var reconciled = ledger.reconcile(request, "fixture-admin");
            assertThat(ledger.reconcile(request, "fixture-admin")).isEqualTo(reconciled);
            assertThat(tokens()).isEqualTo(123);
            assertThat(ledger.pendingCalls(100)).isEmpty();
            assertThat(jdbc.queryForObject("select count(*) from llm_usage_reconciliation where tenant_id=?", Integer.class, tenant)).isEqualTo(1);
            assertThat(jdbc.queryForObject("select json_unquote(json_extract(previous_evidence_json,'$.status')) from llm_usage_reconciliation where tenant_id=?", String.class, tenant))
                .isEqualTo("UNKNOWN");
            var different = new com.repoguard.agent.dto.LlmUsageAccountingDto.ReconciliationRequest(
                "uncertain", "invoice-1", 0, BigDecimal.ZERO, "fixture-invoice-item-1", "Different evidence", true);
            assertThatThrownBy(() -> ledger.reconcile(different, "fixture-admin")).hasMessageContaining("different evidence");
        }
        try (TenantContext.Scope _ = TenantContext.withTenant(tenant + 100000)) {
            var foreign = new com.repoguard.agent.dto.LlmUsageAccountingDto.ReconciliationRequest(
                "uncertain", "foreign-1", 0, BigDecimal.ZERO, "fixture", "Foreign access", true);
            assertThat(ledger.pendingCalls(100)).isEmpty();
            assertThatThrownBy(() -> ledger.reconcile(foreign, "fixture-admin")).hasMessageContaining("unavailable");
        }
    }

    @Test
    void reconciliationCannotReleaseAnActiveCallOrAcceptUnconfirmedEvidence() {
        try (TenantContext.Scope _ = TenantContext.withTenant(tenant)) {
            ledger.reserve(null, "active", "review", settings(), "", "", 100);
            var active = new com.repoguard.agent.dto.LlmUsageAccountingDto.ReconciliationRequest(
                "active", "release-1", 0, BigDecimal.ZERO, "fixture", "Not sent", true);
            assertThatThrownBy(() -> ledger.reconcile(active, "fixture-admin")).hasMessageContaining("active reservation");
            var unconfirmed = new com.repoguard.agent.dto.LlmUsageAccountingDto.ReconciliationRequest(
                "active", "release-1", 0, BigDecimal.ZERO, "fixture", "Not sent", false);
            assertThatThrownBy(() -> ledger.reconcile(unconfirmed, "fixture-admin")).hasMessageContaining("invalid");
            assertThat(tokens()).isEqualTo(1124);
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("fixture latch timeout");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted);
        }
    }

    @Test
    void concurrentIdenticalReconciliationAppliesExactlyOnce() throws Exception {
        try (TenantContext.Scope _ = TenantContext.withTenant(tenant)) {
            assertThatThrownBy(() -> ledger.execute(null, "concurrent", "review", settings(), "", "", 100,
                () -> { throw new IllegalStateException("fixture unknown"); }, result -> null)).hasMessage("fixture unknown");
        }
        var request = new com.repoguard.agent.dto.LlmUsageAccountingDto.ReconciliationRequest(
            "concurrent", "replay-1", 123, new BigDecimal("0.003"), "fixture-invoice", "Final provider usage", true);
        var start = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            java.util.concurrent.Callable<com.repoguard.agent.dto.LlmUsageAccountingDto.ReconciledUsage> action = () -> {
                try (TenantContext.Scope _ = TenantContext.withTenant(tenant)) {
                    await(start); return ledger.reconcile(request, "fixture-admin");
                }
            };
            var first = workers.submit(action); var second = workers.submit(action); start.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(second.get(5, TimeUnit.SECONDS));
        }
        assertThat(tokens()).isEqualTo(123);
        assertThat(jdbc.queryForObject("select count(*) from llm_usage_reconciliation where tenant_id=?", Integer.class, tenant)).isEqualTo(1);
    }

    @Test
    void legacyCurrencyReconciliationClearsOnlyItsUnknownFlagAndKeepsOriginalPricing() {
        jdbc.update("insert into llm_usage_month(tenant_id,usage_month,accounted_tokens,unpriced_calls) values(?,'2026-10-01',100,1)", tenant);
        jdbc.update("""
            insert into llm_usage_call(tenant_id,call_key,usage_month,operation,provider,model_name,request_fingerprint,
                status,accounted_tokens,accounted_cost,pricing_json,usage_json,started_at)
            values(?,'legacy-usd','2026-10-01','legacy','legacy','legacy',?,'LEGACY',100,null,
                JSON_OBJECT('currency','USD','estimatedAmount',1.5),JSON_OBJECT('legacyUnpriced',true),'2026-10-01')
            """, tenant, "a".repeat(64));
        try (TenantContext.Scope _ = TenantContext.withTenant(tenant)) {
            assertThat(ledger.pendingCalls(100)).hasSize(1);
            ledger.reconcile(new com.repoguard.agent.dto.LlmUsageAccountingDto.ReconciliationRequest(
                "legacy-usd", "cny-evidence-1", 100, new BigDecimal("0.002"), "fixture-final-cny-item",
                "Confirmed CNY accounting evidence", true), "fixture-admin");
            assertThat(jdbc.queryForObject("select unpriced_calls from llm_usage_month where tenant_id=?", Long.class, tenant)).isZero();
            assertThat(jdbc.queryForObject("select json_unquote(json_extract(pricing_json,'$.currency')) from llm_usage_call where tenant_id=?", String.class, tenant))
                .isEqualTo("USD");
            assertThat(ledger.pendingCalls(100)).isEmpty();
        }
    }
}
