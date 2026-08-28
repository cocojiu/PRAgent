package com.repoguard.agent.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.EnterpriseTenantQuotaDto;
import com.repoguard.agent.dto.EnterpriseTenantQuotaRequest;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.sql.Date;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class TenantQuotaServiceTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final TenantQuotaService service = new TenantQuotaService(
        jdbcTemplate,
        meterRegistry,
        Clock.fixed(Instant.parse("2026-08-28T12:00:00Z"), ZoneOffset.UTC)
    );

    @Test
    void reservesDailyReviewAtomicallyWhenBelowLimit() {
        when(jdbcTemplate.queryForObject(
            anyString(), eq(Integer.class), eq(8L)
        )).thenReturn(2);
        when(jdbcTemplate.update(
            org.mockito.ArgumentMatchers.contains("insert into tenant_quota_usage"),
            eq(8L), eq(LocalDate.of(2026, 8, 28))
        )).thenReturn(1);
        when(jdbcTemplate.update(
            org.mockito.ArgumentMatchers.contains("review_count = review_count + 1"),
            eq(8L), eq(LocalDate.of(2026, 8, 28)), eq(2)
        )).thenReturn(1);

        service.reserveReview(8L);

        verify(jdbcTemplate).update(
            org.mockito.ArgumentMatchers.contains("insert into tenant_quota_usage"),
            eq(8L), eq(LocalDate.of(2026, 8, 28))
        );
        verify(jdbcTemplate).update(
            org.mockito.ArgumentMatchers.contains("review_count = review_count + 1"),
            eq(8L), eq(LocalDate.of(2026, 8, 28)), eq(2)
        );
    }

    @Test
    void rejectsDailyReviewWhenLimitIsExhausted() {
        when(jdbcTemplate.queryForObject(anyString(), eq(Integer.class), eq(8L))).thenReturn(1);
        when(jdbcTemplate.update(
            org.mockito.ArgumentMatchers.contains("insert into tenant_quota_usage"),
            eq(8L), eq(LocalDate.of(2026, 8, 28))
        )).thenReturn(1);
        when(jdbcTemplate.update(
            org.mockito.ArgumentMatchers.contains("review_count = review_count + 1"),
            eq(8L), eq(LocalDate.of(2026, 8, 28)), eq(1)
        )).thenReturn(0);

        assertThatThrownBy(() -> service.reserveReview(8L))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.TOO_MANY_REQUESTS)
            );
        assertThat(meterRegistry.get("repoguard.tenant.quota.rejected").counter().count()).isEqualTo(1.0);
    }

    @Test
    void readsCurrentUsageAndMapsQuotaColumns() throws Exception {
        Timestamp updatedAt = Timestamp.valueOf("2026-08-28 11:59:00");
        Date usageDate = Date.valueOf("2026-08-28");
        when(jdbcTemplate.query(
            org.mockito.ArgumentMatchers.contains("tenant_quota_config"),
            org.mockito.ArgumentMatchers.<RowMapper<EnterpriseTenantQuotaDto>>any(),
            eq(LocalDate.of(2026, 8, 28)),
            eq(LocalDate.of(2026, 8, 28)),
            eq("acme")
        )).thenAnswer(invocation -> {
            RowMapper<EnterpriseTenantQuotaDto> mapper = invocation.getArgument(1);
            ResultSet resultSet = mock(ResultSet.class);
            when(resultSet.getLong("tenant_id")).thenReturn(8L);
            when(resultSet.getString("tenant_key")).thenReturn("acme");
            when(resultSet.getLong("quota_version")).thenReturn(3L);
            when(resultSet.getInt("max_daily_reviews")).thenReturn(500);
            when(resultSet.getInt("used_reviews")).thenReturn(12);
            when(resultSet.getDate("usage_date")).thenReturn(usageDate);
            when(resultSet.getTimestamp("updated_at")).thenReturn(updatedAt);
            return List.of(mapper.mapRow(resultSet, 0));
        });

        EnterpriseTenantQuotaDto quota = service.get(" ACME ");

        assertThat(quota.tenantId()).isEqualTo(8L);
        assertThat(quota.tenantKey()).isEqualTo("acme");
        assertThat(quota.quotaVersion()).isEqualTo(3L);
        assertThat(quota.maxDailyReviews()).isEqualTo(500);
        assertThat(quota.usedReviews()).isEqualTo(12);
        assertThat(quota.usageDate()).isEqualTo(LocalDate.of(2026, 8, 28));
        assertThat(quota.updatedAt()).isEqualTo(updatedAt.toLocalDateTime());
    }

    @Test
    void updatesQuotaWithExpectedVersionAndReturnsFreshUsage() throws Exception {
        when(jdbcTemplate.update(
            org.mockito.ArgumentMatchers.contains("update tenant_quota_config"),
            eq(500), eq("acme"), eq(2L)
        )).thenReturn(1);
        when(jdbcTemplate.query(
            org.mockito.ArgumentMatchers.contains("tenant_quota_config"),
            org.mockito.ArgumentMatchers.<RowMapper<EnterpriseTenantQuotaDto>>any(),
            eq(LocalDate.of(2026, 8, 28)),
            eq(LocalDate.of(2026, 8, 28)),
            eq("acme")
        )).thenReturn(List.of(new EnterpriseTenantQuotaDto(
            8L, "acme", 3L, 500, 12, LocalDate.of(2026, 8, 28),
            LocalDate.of(2026, 8, 28).atTime(11, 59)
        )));

        EnterpriseTenantQuotaDto result = service.update(
            " acme ", new EnterpriseTenantQuotaRequest(2L, 500)
        );

        assertThat(result.maxDailyReviews()).isEqualTo(500);
        assertThat(result.quotaVersion()).isEqualTo(3L);
    }

    @Test
    void rejectsStaleQuotaVersionAndInvalidTenant() {
        when(jdbcTemplate.update(
            org.mockito.ArgumentMatchers.contains("update tenant_quota_config"),
            eq(500), eq("acme"), eq(2L)
        )).thenReturn(0);

        assertThatThrownBy(() -> service.update(
            "acme", new EnterpriseTenantQuotaRequest(2L, 500)
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
            assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.CONFLICT)
        );
        assertThatThrownBy(() -> service.reserveReview(0L))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.get(" "))
            .isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(ErrorCode.BAD_REQUEST)
            );
    }
}
