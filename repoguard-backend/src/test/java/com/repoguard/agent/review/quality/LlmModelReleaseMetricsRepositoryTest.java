package com.repoguard.agent.review.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.tenancy.TenantContext;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.PreparedStatementCreator;
import org.springframework.jdbc.core.RowMapper;

class LlmModelReleaseMetricsRepositoryTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final LlmModelReleaseMetricsRepository repository = new LlmModelReleaseMetricsRepository(jdbcTemplate);

    @Test
    void aggregateNormalizesDatabaseValuesAndComputesP95() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 3, 1, 0);
        LocalDateTime end = start.plusHours(1);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
            "sample_count", "12",
            "total_tokens", -2,
            "total_cost", new BigDecimal("1.25"),
            "parse_failures", 2,
            "fallbacks", "bad"
        )));
        List<Long> durations = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L,
            11L, 12L, 13L, 14L, 15L, 16L, 17L, 18L, 19L, 20L);
        when(jdbcTemplate.query(contains("select llm_duration_ms"),
            ArgumentMatchers.<RowMapper<Long>>any(), any(Object[].class))).thenAnswer(invocation -> {
                RowMapper<Long> mapper = invocation.getArgument(1);
                return durations.stream().map(value -> {
                    try {
                        ResultSet row = mock(ResultSet.class);
                        when(row.getLong(1)).thenReturn(value);
                        return mapper.mapRow(row, 0);
                    } catch (Exception exception) {
                        throw new AssertionError(exception);
                    }
                }).toList();
            });

        LlmModelReleaseMetricsRepository.RuntimeAggregate aggregate = repository.aggregate(42L, "release-1", start, end);

        assertThat(aggregate.sampleCount()).isEqualTo(12L);
        assertThat(aggregate.totalTokens()).isZero();
        assertThat(aggregate.totalCost()).isEqualByComparingTo("1.25");
        assertThat(aggregate.p95LatencyMs()).isEqualTo(19L);
        assertThat(aggregate.parseFailureCount()).isEqualTo(2L);
        assertThat(aggregate.fallbackCount()).isZero();
    }

    @Test
    void aggregateHandlesEmptyAndMalformedAggregateRows() {
        LocalDateTime start = LocalDateTime.of(2026, 9, 3, 1, 0);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());
        when(jdbcTemplate.query(contains("select llm_duration_ms"),
            ArgumentMatchers.<RowMapper<Long>>any(), any(Object[].class))).thenReturn(List.of());

        assertThat(repository.aggregate(42L, "release-1", start, start.plusHours(1)))
            .extracting(
                LlmModelReleaseMetricsRepository.RuntimeAggregate::sampleCount,
                LlmModelReleaseMetricsRepository.RuntimeAggregate::totalTokens,
                LlmModelReleaseMetricsRepository.RuntimeAggregate::totalCost,
                LlmModelReleaseMetricsRepository.RuntimeAggregate::p95LatencyMs
            ).containsExactly(0L, 0L, BigDecimal.ZERO, 0L);

        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of(Map.of(
            "sample_count", "not-a-number",
            "total_tokens", "not-a-number",
            "total_cost", "not-a-number",
            "parse_failures", -3L,
            "fallbacks", 4L
        )));
        assertThat(repository.aggregate(42L, "release-1", start, start.plusHours(1)).sampleCount()).isZero();
        assertThat(repository.aggregate(42L, "release-1", start, start.plusHours(1)).totalCost()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void countRollbacksClampsNullAndNegativeValues() {
        LocalDateTime start = LocalDateTime.of(2026, 9, 3, 1, 0);
        when(jdbcTemplate.queryForObject(anyString(), eq(Long.class), any(Object[].class))).thenReturn(null, -2L, 3L);

        assertThat(repository.countRollbacks(42L, 7L, start, start.plusHours(1))).isZero();
        assertThat(repository.countRollbacks(42L, 7L, start, start.plusHours(1))).isZero();
        assertThat(repository.countRollbacks(42L, 7L, start, start.plusHours(1))).isEqualTo(3L);
    }

    @Test
    void findMethodsMapRowsAndClampLimitsAcrossTenantBoundary() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 3, 1, 0);
        ResultSet resultSet = snapshotRow();
        when(jdbcTemplate.query(contains("where tenant_id = ? and release_id = ?"),
            ArgumentMatchers.<RowMapper<LlmModelReleaseMetricSnapshot>>any(), eq(new Object[] {1L, 7L, start, start.plusHours(1)})))
            .thenAnswer(invocation -> List.of(invocation.<RowMapper<LlmModelReleaseMetricSnapshot>>getArgument(1).mapRow(resultSet, 0)));
        when(jdbcTemplate.query(contains("window_start >= ?"),
            ArgumentMatchers.<RowMapper<LlmModelReleaseMetricSnapshot>>any(), eq(new Object[] {42L, start, 1})))
            .thenAnswer(invocation -> List.of(invocation.<RowMapper<LlmModelReleaseMetricSnapshot>>getArgument(1).mapRow(resultSet, 0)));
        when(jdbcTemplate.query(contains("window_start >= ?"),
            ArgumentMatchers.<RowMapper<LlmModelReleaseMetricSnapshot>>any(), eq(new Object[] {42L, start, "release-1", 500})))
            .thenAnswer(invocation -> List.of(invocation.<RowMapper<LlmModelReleaseMetricSnapshot>>getArgument(1).mapRow(resultSet, 0)));

        try (TenantContext.Scope _ = TenantContext.withTenant(1L)) {
            assertThat(repository.findOne(7L, start, start.plusHours(1))).isNotNull()
                .extracting(LlmModelReleaseMetricSnapshot::alertCodes).isEqualTo(List.of("P95", "COST"));
        }
        assertThat(repository.findSnapshots(42L, null, start, 0)).hasSize(1);
        assertThat(repository.findSnapshots(42L, "release-1", start, 600)).hasSize(1);
        verify(jdbcTemplate).query(contains("window_start >= ?"),
            ArgumentMatchers.<RowMapper<LlmModelReleaseMetricSnapshot>>any(), eq(new Object[] {42L, start, 1}));
    }

    @Test
    void upsertBindsAllValuesAndReloadsFreshSnapshot() throws Exception {
        LocalDateTime start = LocalDateTime.of(2026, 9, 3, 1, 0);
        LocalDateTime end = start.plusHours(1);
        LlmModelReleaseMetricSnapshot snapshot = new LlmModelReleaseMetricSnapshot(
            null, 7L, "release-1", "openai", "gpt-next", start, end, 12L, 1200L,
            new BigDecimal("1.20"), 800L, 1L, 2L, 0L, "ALERT", List.of("P95", "COST"),
            "NOTIFY", "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef", null, null
        );
        Connection connection = mock(Connection.class);
        PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.prepareStatement(anyString())).thenReturn(statement);
        when(jdbcTemplate.update(any(PreparedStatementCreator.class))).thenAnswer(invocation -> {
            PreparedStatementCreator creator = invocation.getArgument(0);
            creator.createPreparedStatement(connection);
            return 1;
        });
        ResultSet row = snapshotRow();
        when(jdbcTemplate.query(contains("where tenant_id = ? and release_id = ?"),
            ArgumentMatchers.<RowMapper<LlmModelReleaseMetricSnapshot>>any(), eq(new Object[] {1L, 7L, start, end})))
            .thenAnswer(invocation -> List.of(invocation.<RowMapper<LlmModelReleaseMetricSnapshot>>getArgument(1).mapRow(row, 0)));

        LlmModelReleaseMetricSnapshot loaded = repository.upsert(snapshot);

        assertThat(loaded.releaseKey()).isEqualTo("release-1");
        verify(statement).setObject(1, 1L);
        verify(statement).setObject(2, 7L);
        verify(statement).setObject(17, "NOTIFY");
        verify(statement).setObject(18, snapshot.alertFingerprint());
    }

    @Test
    void snapshotRecordNormalizesNullAndNegativeValues() {
        LlmModelReleaseMetricSnapshot snapshot = new LlmModelReleaseMetricSnapshot(
            null, null, null, null, null, null, null, -1L, null, null, -2L, -3L, -4L, -5L,
            null, null, null, null, null, null
        );

        assertThat(snapshot.sampleCount()).isZero();
        assertThat(snapshot.totalTokens()).isZero();
        assertThat(snapshot.totalCost()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(snapshot.alertCodes()).isEmpty();
    }

    private ResultSet snapshotRow() throws Exception {
        ResultSet row = mock(ResultSet.class);
        when(row.getLong("id")).thenReturn(99L);
        when(row.getLong("release_id")).thenReturn(7L);
        when(row.getString("release_key")).thenReturn("release-1");
        when(row.getString("provider")).thenReturn("openai");
        when(row.getString("model_name")).thenReturn("gpt-next");
        when(row.getTimestamp("window_start")).thenReturn(Timestamp.valueOf("2026-09-03 01:00:00"));
        when(row.getTimestamp("window_end")).thenReturn(Timestamp.valueOf("2026-09-03 02:00:00"));
        when(row.getLong("sample_count")).thenReturn(12L);
        when(row.getLong("total_tokens")).thenReturn(1200L);
        when(row.getBigDecimal("total_cost")).thenReturn(new BigDecimal("1.20"));
        when(row.getLong("p95_latency_ms")).thenReturn(800L);
        when(row.getLong("parse_failure_count")).thenReturn(1L);
        when(row.getLong("fallback_count")).thenReturn(2L);
        when(row.getLong("rollback_count")).thenReturn(0L);
        when(row.getString("alert_state")).thenReturn("ALERT");
        when(row.getString("alert_codes")).thenReturn("P95, ,COST");
        when(row.getString("action")).thenReturn("NOTIFY");
        when(row.getString("alert_fingerprint")).thenReturn("fingerprint");
        when(row.getTimestamp("created_at")).thenReturn(null);
        when(row.getTimestamp("updated_at")).thenReturn(null);
        return row;
    }
}
