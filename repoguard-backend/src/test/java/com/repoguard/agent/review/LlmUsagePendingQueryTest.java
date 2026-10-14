package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.dto.LlmUsageAccountingDto.PendingUsage;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.sql.ResultSet;
import com.repoguard.agent.tenancy.TenantProperties;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class LlmUsagePendingQueryTest {
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
    private final LlmUsagePendingQuery query = new LlmUsagePendingQuery(jdbc, new ObjectMapper());

    @Test
    void lookaheadDoesNotExposeTheExtraRowAndFinalPageHasNoCursor() {
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<PendingUsage>>any(), any(Object[].class)))
            .thenReturn(List.of(row("a"), row("b"), row("c"))).thenReturn(List.of(row("c")));
        var first = query.page(1, 2, null, null, null);
        assertThat(first.items()).extracting(PendingUsage::callKey).containsExactly("a", "b");
        assertThat(first.hasMore()).isTrue();
        assertThat(first.nextCursor()).isNotBlank();
        var last = query.page(1, 2, null, null, first.nextCursor());
        assertThat(last.items()).extracting(PendingUsage::callKey).containsExactly("c");
        assertThat(last.hasMore()).isFalse();
        assertThat(last.nextCursor()).isNull();
    }

    @Test
    void cursorsCannotBeReusedWithAnotherTenantOrFilter() {
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<PendingUsage>>any(), any(Object[].class)))
            .thenReturn(List.of(row("a"), row("b")));
        String cursor = query.page(1, 1, "2026-10", "UNKNOWN", null).nextCursor();
        clearInvocations(jdbc);
        assertThatThrownBy(() -> query.page(2, 1, "2026-10", "UNKNOWN", cursor)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> query.page(1, 1, "2026-11", "UNKNOWN", cursor)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> query.page(1, 1, "2026-10", "RESERVED", cursor)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void invalidInputIsRejectedBeforeReadingTheDatabase() {
        for (String cursor : List.of("", " ", "?", "e30", "bnVsbA", "a".repeat(1025))) {
            assertThatThrownBy(() -> query.page(1, 10, null, null, cursor)).isInstanceOf(BusinessException.class);
        }
        for (String month : List.of("2026-00", "2026-13", "2026-1", "0999-10", "2026-10' OR 1=1")) {
            assertThatThrownBy(() -> query.page(1, 10, month, null, null)).isInstanceOf(BusinessException.class);
        }
        assertThatThrownBy(() -> query.page(1, 101, null, null, null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> query.page(1, 0, null, null, null)).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> query.page(1, 10, null, "SETTLED", null)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void emptyPageIsTerminal() {
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<PendingUsage>>any(), any(Object[].class)))
            .thenReturn(List.of());
        var page = query.page(1, 100, null, null, null);
        assertThat(page.items()).isEmpty();
        assertThat(page.hasMore()).isFalse();
        assertThat(page.nextCursor()).isNull();
    }

    @Test
    void enterpriseDiagnosticsRequireAnExplicitTenantContext() {
        var tenancy = new TenantProperties();
        tenancy.setEnabled(true);
        var tenantQuery = new LlmUsagePendingQuery(jdbc, new ObjectMapper(), tenancy);
        assertThatThrownBy(() -> tenantQuery.page(10, null, null, null)).isInstanceOf(BusinessException.class);
        verifyNoInteractions(jdbc);
    }

    @Test
    void jdbcMappingPreservesUtcMicrosecondsAndNullableTaskReferences() throws Exception {
        ResultSet result = mock(ResultSet.class);
        when(result.getString("call_key")).thenReturn("legacy-checkpoint");
        when(result.getString("status")).thenReturn("LEGACY");
        when(result.getObject("usage_month", LocalDate.class)).thenReturn(LocalDate.of(2026, 10, 1));
        when(result.getObject("started_at", LocalDateTime.class)).thenReturn(LocalDateTime.parse("2026-10-14T02:00:00.123456"));
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<PendingUsage>>any(), any(Object[].class)))
            .thenAnswer(invocation -> {
                RowMapper<PendingUsage> mapper = invocation.getArgument(1);
                return List.of(mapper.mapRow(result, 0));
            });
        var page = query.page(1, 100, "2026-10", "LEGACY", null);
        assertThat(page.items()).singleElement().satisfies(value -> {
            assertThat(value.callKey()).isEqualTo("legacy-checkpoint");
            assertThat(value.month()).isEqualTo(LocalDate.of(2026, 10, 1));
            assertThat(value.startedAt().getOffset()).isEqualTo(ZoneOffset.UTC);
            assertThat(value.startedAt().getNano()).isEqualTo(123456000);
            assertThat(value.taskId()).isNull();
            assertThat(value.attemptId()).isNull();
        });
    }

    private static PendingUsage row(String key) {
        return new PendingUsage(key, LocalDate.of(2026, 10, 1), null, null, "review", "fixture", "fixture",
            "UNKNOWN", 123, BigDecimal.ONE, OffsetDateTime.parse("2026-10-14T02:00:00.123456Z"));
    }
}
