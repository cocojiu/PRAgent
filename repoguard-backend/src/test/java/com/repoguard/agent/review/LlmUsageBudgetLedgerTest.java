package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.dto.LlmUsageAccountingDto.ReconciliationRequest;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.tenancy.TenantContext;
import com.repoguard.agent.tenancy.TenantProperties;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.SimpleTransactionStatus;

class LlmUsageBudgetLedgerTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-10-31T23:59:59Z"), ZoneOffset.UTC);
    private final LlmUsageBudgetLedger ledger = new LlmUsageBudgetLedger(jdbc, manager, new ObjectMapper(), clock);

    @BeforeEach
    void defaults() {
        when(manager.getTransaction(any())).thenAnswer(invocation -> new SimpleTransactionStatus());
        when(jdbc.queryForList(contains("tenant_quota_config"), any(Object[].class))).thenReturn(List.of(
            Map.of("monthly_llm_token_budget", 10000L, "monthly_llm_cost_budget", BigDecimal.TEN)));
        when(jdbc.queryForMap(contains("unpriced_calls"), any(Object[].class))).thenReturn(
            Map.of("accounted_tokens", 0L, "accounted_cost", BigDecimal.ZERO, "unpriced_calls", 0L, "legacy_unknown_attempts", 0L));
        when(jdbc.queryForMap(contains("from llm_usage_call"), any(Object[].class))).thenReturn(
            Map.of("status", "RESERVED", "accounted_tokens", 356L, "accounted_cost", new BigDecimal("0.001312")));
        when(jdbc.update(startsWith("insert into llm_usage_call"), any(Object[].class))).thenReturn(1);
    }

    @Test
    void finiteBudgetRejectsBeforeProviderWorkAndRollsBack() {
        when(jdbc.queryForList(contains("tenant_quota_config"), any(Object[].class))).thenReturn(List.of(
            Map.of("monthly_llm_token_budget", 100L, "monthly_llm_cost_budget", BigDecimal.ZERO)));
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> ledger.execute(null, "request-1", "review", LlmUsageBudgetMathTest.settings(),
            "", "", 100, () -> calls.incrementAndGet(), value -> new LlmCallResult("", 10, 1, 11)))
            .isInstanceOf(LlmUsageBudgetLedger.AdmissionDeniedException.class).hasMessageContaining("llm_monthly_budget_exhausted");
        assertThat(calls).hasValue(0);
        verify(manager).rollback(any());
        verify(jdbc, never()).update(startsWith("insert into llm_usage_call"), any(Object[].class));
    }

    @Test
    void successfulCallReservesInNewTransactionAndSettlesInItsOriginalUtcMonth() {
        ReviewTask task = new ReviewTask(); task.setId(12L); task.setCurrentAttemptId(15L);
        try (TenantContext.Scope _ = TenantContext.withTenant(42L)) {
            var result = ledger.execute(task, "request-2", "high_risk_verification", LlmUsageBudgetMathTest.settings(),
                "", "", 100, () -> "response", response -> new LlmCallResult("", 100, 10, 110));
            assertThat(result).isEqualTo("response");
        }
        verify(jdbc).update(startsWith("insert into llm_usage_month"), eq(42L), eq(Date.valueOf("2026-10-01")));
        var transactions = org.mockito.ArgumentCaptor.forClass(TransactionDefinition.class);
        verify(manager, times(2)).getTransaction(transactions.capture());
        assertThat(transactions.getAllValues()).allSatisfy(definition -> {
            assertThat(definition.getPropagationBehavior()).isEqualTo(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
            assertThat(definition.getTimeout()).isEqualTo(3);
        });
        verify(manager, times(2)).commit(any());
    }

    @Test
    void transportFailurePreservesReservedAccountingAndRegistersUnknownUsage() {
        AtomicInteger calls = new AtomicInteger();
        assertThatThrownBy(() -> ledger.execute(null, "request-3", "review", LlmUsageBudgetMathTest.settings(),
            "", "", 100, () -> { calls.incrementAndGet(); throw new IllegalStateException("fixture failure"); },
            response -> new LlmCallResult("", 100, 10, 110))).hasMessage("fixture failure");
        assertThat(calls).hasValue(1);
        verify(jdbc).update(contains("set status='UNKNOWN'"), eq(1L), eq("request-3"));
        verify(manager, times(1)).commit(any());
    }

    @Test
    void establishedSettlementsAreImmutableUnderDuplicateCallbacks() {
        when(jdbc.queryForMap(contains("from llm_usage_call"), any(Object[].class))).thenReturn(
            Map.of("status", "SETTLED", "accounted_tokens", 110L, "accounted_cost", new BigDecimal("0.00028")));
        var reservation = new LlmUsageBudgetLedger.Reservation(1, "settled", Date.valueOf("2026-10-01"),
            new LlmUsageBudgetMath.Bound(356, new BigDecimal("0.001312")));
        ledger.settle(reservation, LlmUsageBudgetMathTest.settings(), new LlmCallResult("", 999, 999, 1998));
        verify(jdbc, never()).update(contains("update llm_usage_call"), any(Object[].class));
        verify(jdbc, never()).update(contains("update llm_usage_month"), any(Object[].class));
    }

    @Test
    void legacyUnknownUsageAndMissingTenantContextFailClosed() {
        when(jdbc.queryForMap(contains("unpriced_calls"), any(Object[].class))).thenReturn(
            Map.of("accounted_tokens", 0L, "accounted_cost", BigDecimal.ZERO, "unpriced_calls", 0L, "legacy_unknown_attempts", 1L));
        assertThatThrownBy(() -> ledger.reserve(null, "unknown", "review", LlmUsageBudgetMathTest.settings(), "", "", 100))
            .hasMessageContaining("llm_budget_legacy_usage_unknown");
        TenantProperties tenancy = new TenantProperties(); tenancy.setEnabled(true);
        var scoped = new LlmUsageBudgetLedger(jdbc, manager, new ObjectMapper(), clock, tenancy);
        assertThatThrownBy(() -> scoped.reserve(null, "missing-tenant", "review", LlmUsageBudgetMathTest.settings(), "", "", 100))
            .hasMessageContaining("llm_budget_tenant_context_missing");
    }

    @Test
    void finalEvidenceAdjustsOnlyTheDifferenceAndReplayKeepsOriginalOperatorAndAudit() {
        var request = reconciliationRequest(450, new BigDecimal("0.00200000"), true);
        var previous = accountingRecord("UNKNOWN", LocalDateTime.of(2026, 10, 30, 12, 0));
        previous.put("accounted_tokens", 1000L);
        previous.put("accounted_cost", new BigDecimal("0.01000000"));
        when(jdbc.queryForList(contains("select usage_month"), any(Object[].class)))
            .thenReturn(List.of(Map.of("usage_month", Date.valueOf("2026-10-01"))));
        when(jdbc.queryForMap(contains("from llm_usage_call"), any(Object[].class))).thenReturn(previous);
        AtomicReference<Map<String, Object>> audit = new AtomicReference<>();
        when(jdbc.queryForList(contains("from llm_usage_reconciliation"), any(Object[].class)))
            .thenAnswer(_ -> audit.get() == null ? List.of() : List.of(audit.get()));
        when(jdbc.update(startsWith("insert into llm_usage_reconciliation"), any(Object[].class)))
            .thenAnswer(invocation -> {
                Object[] values = invocation.getArguments();
                audit.set(Map.of("request_fingerprint", values[4], "call_key", values[3],
                    "accounted_tokens", values[9], "accounted_cost", values[10],
                    "operator_name", values[5], "evidence_reference", values[6], "reason", values[7],
                    "created_at", java.sql.Timestamp.valueOf((LocalDateTime) values[11])));
                return 1;
            });

        var first = ledger.reconcile(request, "operator-first");
        var repeated = ledger.reconcile(request, "operator-retrying");
        assertThat(repeated).isEqualTo(first);
        assertThat(first.operator()).isEqualTo("operator-first");
        assertThat(first.accountedTokens()).isEqualTo(450);
        assertThat(first.accountedCost()).isEqualByComparingTo("0.002");
        assertThat(first.reconciledAt().toInstant()).isEqualTo(clock.instant());
        verify(jdbc).update(contains("legacy_unknown_attempts=legacy_unknown_attempts-?"),
            eq(-550L), eq(new BigDecimal("-0.00800000")), eq(0), eq(0),
            eq(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC)), eq(1L), eq(Date.valueOf("2026-10-01")));
        verify(jdbc, times(1)).update(startsWith("insert into llm_usage_reconciliation"), any(Object[].class));
        assertThatThrownBy(() -> ledger.reconcile(reconciliationRequest(451, request.accountedCost(), true), "operator-first"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("different evidence");
        verify(jdbc, times(1)).update(startsWith("update llm_usage_call"), any(Object[].class));
    }

    @Test
    void confirmedUsageAndActiveReservationsCannotBeReleasedByAnOperator() {
        when(jdbc.queryForList(contains("select usage_month"), any(Object[].class)))
            .thenReturn(List.of(Map.of("usage_month", Date.valueOf("2026-10-01"))));
        var request = reconciliationRequest(0, BigDecimal.ZERO, true);
        when(jdbc.queryForMap(contains("from llm_usage_call"), any(Object[].class)))
            .thenReturn(accountingRecord("SETTLED", LocalDateTime.of(2026, 10, 30, 12, 0)));
        assertThatThrownBy(() -> ledger.reconcile(request, "operator"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("immutable");
        when(jdbc.queryForMap(contains("from llm_usage_call"), any(Object[].class)))
            .thenReturn(accountingRecord("RESERVED", LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC).minusMinutes(30)));
        assertThatThrownBy(() -> ledger.reconcile(request, "operator"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("active reservation");
        verify(jdbc, never()).update(contains("update llm_usage_month"), any(Object[].class));
        verify(jdbc, never()).update(startsWith("insert into llm_usage_reconciliation"), any(Object[].class));
        verify(manager, times(2)).rollback(any());
    }

    @Test
    void missingAccountingRecordCannotCreateASettlementOrAnAudit() {
        assertThatThrownBy(() -> ledger.reconcile(reconciliationRequest(0, BigDecimal.ZERO, true), "operator"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("unavailable");
        verify(jdbc, never()).update(anyString(), any(Object[].class));
        verify(manager).rollback(any());
    }

    @Test
    void incompleteEvidenceAndUnsafeAmountsFailBeforeOpeningATransaction() {
        for (var request : List.of(reconciliationRequest(0, BigDecimal.ZERO, false),
            reconciliationRequest(-1, BigDecimal.ZERO, true),
            reconciliationRequest(9_007_199_254_740_992L, BigDecimal.ZERO, true),
            reconciliationRequest(0, new BigDecimal("-0.01"), true),
            reconciliationRequest(0, new BigDecimal("0.000000001"), true),
            reconciliationRequest(0, new BigDecimal("100000000000000"), true))) {
            assertThatThrownBy(() -> ledger.reconcile(request, "operator"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("invalid");
        }
        assertThatThrownBy(() -> ledger.reconcile(null, "operator")).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> ledger.reconcile(reconciliationRequest(0, BigDecimal.ZERO, true), " "))
            .isInstanceOf(BusinessException.class);
        verify(manager, never()).getTransaction(any());
        verify(jdbc, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void boundReleaseRequiresExistingNonRevokedVersionBeforeSending() {
        var task = new ReviewTask(); task.setLlmReleaseKey("release-fixture");
        assertThatThrownBy(() -> ledger.requireReleaseAllowed(task))
            .isInstanceOf(LlmUsageBudgetLedger.AdmissionDeniedException.class).hasMessageContaining("safety_revoked");
        when(jdbc.queryForList(contains("from llm_model_release"), any(Object[].class)))
            .thenReturn(List.of(Map.of("safety_revoked_at", LocalDateTime.of(2026, 10, 30, 12, 0))));
        assertThatThrownBy(() -> ledger.requireReleaseAllowed(task))
            .isInstanceOf(LlmUsageBudgetLedger.AdmissionDeniedException.class).hasMessageContaining("safety_revoked");
        Map<String, Object> allowed = new HashMap<>(); allowed.put("safety_revoked_at", null);
        when(jdbc.queryForList(contains("from llm_model_release"), any(Object[].class))).thenReturn(List.of(allowed));
        ledger.requireReleaseAllowed(task);
        verify(manager, times(2)).rollback(any());
        verify(manager).commit(any());
    }

    private ReconciliationRequest reconciliationRequest(long tokens, BigDecimal cost, boolean finalEvidence) {
        return new ReconciliationRequest("call-fixture", "evidence-fixture", tokens, cost,
            "provider-invoice-fixture", "Final provider evidence reviewed", finalEvidence);
    }

    private Map<String, Object> accountingRecord(String status, LocalDateTime started) {
        Map<String, Object> row = new HashMap<>(); row.put("status", status);
        row.put("accounted_tokens", 1000L); row.put("accounted_cost", new BigDecimal("0.01000000"));
        row.put("pricing_json", "{\"currency\":\"CNY\",\"pricingVersion\":\"fixture\"}");
        row.put("usage_json", "{}"); row.put("started_at", started); return row;
    }
}
