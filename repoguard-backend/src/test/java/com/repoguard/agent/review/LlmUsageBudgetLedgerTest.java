package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.tenancy.TenantContext;
import com.repoguard.agent.tenancy.TenantProperties;
import java.math.BigDecimal;
import java.sql.Date;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
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
}
