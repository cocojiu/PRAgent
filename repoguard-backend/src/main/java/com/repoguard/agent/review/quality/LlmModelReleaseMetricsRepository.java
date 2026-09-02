package com.repoguard.agent.review.quality;

import java.math.BigDecimal;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence boundary for bounded, idempotent runtime release snapshots. */
@Repository
public class LlmModelReleaseMetricsRepository {

    private final JdbcTemplate jdbcTemplate;

    public LlmModelReleaseMetricsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public RuntimeAggregate aggregate(long tenantId, String releaseKey, LocalDateTime start, LocalDateTime end) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList("""
            select count(*) as sample_count,
                   coalesce(sum(coalesce(llm_total_tokens, 0)), 0) as total_tokens,
                   coalesce(sum(coalesce(llm_estimated_cost, 0)), 0) as total_cost,
                   coalesce(sum(case when lower(coalesce(llm_parse_status_norm, '')) in
                       ('failed', 'failure', 'fallback', 'partial_fallback') then 1 else 0 end), 0) as parse_failures,
                   coalesce(sum(case when nullif(trim(coalesce(llm_fallback_reason, '')), '') is not null
                       or lower(coalesce(llm_parse_status_norm, '')) in ('fallback', 'partial_fallback')
                       then 1 else 0 end), 0) as fallbacks
              from review_task
             where tenant_id = ? and llm_release_key = ?
               and created_at >= ? and created_at < ?
               and lower(coalesce(llm_status_norm, '')) = 'completed'
            """, tenantId, releaseKey, start, end);
        Map<String, Object> row = rows.isEmpty() ? Map.of() : rows.getFirst();
        List<Long> durations = jdbcTemplate.query(
            """
                select llm_duration_ms
                  from review_task
                 where tenant_id = ? and llm_release_key = ?
                   and created_at >= ? and created_at < ?
                   and lower(coalesce(llm_status_norm, '')) = 'completed'
                   and llm_duration_ms is not null and llm_duration_ms >= 0
                 order by llm_duration_ms asc
                 limit 5000
                """,
            (rs, rowNum) -> rs.getLong(1), tenantId, releaseKey, start, end
        );
        return new RuntimeAggregate(
            number(row.get("sample_count")),
            number(row.get("total_tokens")),
            decimal(row.get("total_cost")),
            percentile95(durations),
            number(row.get("parse_failures")),
            number(row.get("fallbacks"))
        );
    }

    public long countRollbacks(long tenantId, long releaseId, LocalDateTime start, LocalDateTime end) {
        Long count = jdbcTemplate.queryForObject("""
            select count(*) from llm_model_release_audit
             where tenant_id = ? and release_id = ?
               and action in ('ROLLBACK', 'AUTO_ROLLBACK')
               and created_at >= ? and created_at < ?
            """, Long.class, tenantId, releaseId, start, end);
        return count == null ? 0L : Math.max(0L, count);
    }

    public LlmModelReleaseMetricSnapshot upsert(LlmModelReleaseMetricSnapshot snapshot) {
        jdbcTemplate.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                insert into llm_model_release_metric_snapshot
                    (tenant_id, release_id, release_key, provider, model_name, window_start, window_end,
                     sample_count, total_tokens, total_cost, p95_latency_ms, parse_failure_count, fallback_count,
                     rollback_count, alert_state, alert_codes, action, alert_fingerprint, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp(6), current_timestamp(6))
                on duplicate key update
                    sample_count = values(sample_count), total_tokens = values(total_tokens),
                    total_cost = values(total_cost), p95_latency_ms = values(p95_latency_ms),
                    parse_failure_count = values(parse_failure_count), fallback_count = values(fallback_count),
                    rollback_count = values(rollback_count), alert_state = values(alert_state),
                    alert_codes = values(alert_codes), action = values(action),
                    alert_fingerprint = values(alert_fingerprint), updated_at = current_timestamp(6)
                """);
            bind(statement, snapshot);
            return statement;
        });
        return findOne(snapshot.releaseId(), snapshot.windowStart(), snapshot.windowEnd());
    }

    public LlmModelReleaseMetricSnapshot findOne(long releaseId, LocalDateTime start, LocalDateTime end) {
        List<LlmModelReleaseMetricSnapshot> rows = jdbcTemplate.query("""
            select * from llm_model_release_metric_snapshot
             where tenant_id = ? and release_id = ? and window_start = ? and window_end = ?
            """, this::map, currentTenant(), releaseId, start, end);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    public List<LlmModelReleaseMetricSnapshot> findSnapshots(long tenantId, String releaseKey,
        LocalDateTime start, int limit) {
        String sql = """
            select * from llm_model_release_metric_snapshot
             where tenant_id = ? and window_start >= ?
            """ + (releaseKey == null ? "" : " and release_key = ? ")
            + " order by window_start desc, id desc limit ?";
        return releaseKey == null
            ? jdbcTemplate.query(sql, this::map, tenantId, start, Math.max(1, Math.min(500, limit)))
            : jdbcTemplate.query(sql, this::map, tenantId, start, releaseKey, Math.max(1, Math.min(500, limit)));
    }

    private void bind(PreparedStatement statement, LlmModelReleaseMetricSnapshot snapshot) throws SQLException {
        Object[] values = {
            currentTenant(), snapshot.releaseId(), snapshot.releaseKey(), snapshot.provider(), snapshot.modelName(),
            snapshot.windowStart(), snapshot.windowEnd(), snapshot.sampleCount(), snapshot.totalTokens(), snapshot.totalCost(),
            snapshot.p95LatencyMs(), snapshot.parseFailureCount(), snapshot.fallbackCount(), snapshot.rollbackCount(),
            snapshot.alertState(), String.join(",", snapshot.alertCodes()), snapshot.action(), snapshot.alertFingerprint()
        };
        for (int index = 0; index < values.length; index++) {
            statement.setObject(index + 1, values[index]);
        }
    }

    private LlmModelReleaseMetricSnapshot map(ResultSet rs, int rowNum) throws SQLException {
        return new LlmModelReleaseMetricSnapshot(
            rs.getLong("id"), rs.getLong("release_id"), rs.getString("release_key"), rs.getString("provider"),
            rs.getString("model_name"), timestamp(rs, "window_start"), timestamp(rs, "window_end"),
            rs.getLong("sample_count"), rs.getLong("total_tokens"), rs.getBigDecimal("total_cost"),
            rs.getLong("p95_latency_ms"), rs.getLong("parse_failure_count"), rs.getLong("fallback_count"),
            rs.getLong("rollback_count"), rs.getString("alert_state"), split(rs.getString("alert_codes")),
            rs.getString("action"), rs.getString("alert_fingerprint"), timestamp(rs, "created_at"), timestamp(rs, "updated_at")
        );
    }

    private LocalDateTime timestamp(ResultSet rs, String name) throws SQLException {
        java.sql.Timestamp value = rs.getTimestamp(name);
        return value == null ? null : value.toLocalDateTime();
    }

    private List<String> split(String value) {
        return value == null || value.isBlank() ? List.of() : java.util.Arrays.stream(value.split(","))
            .map(String::trim).filter(item -> !item.isBlank()).toList();
    }

    private long currentTenant() {
        return com.repoguard.agent.tenancy.TenantContext.currentTenantIdOrDefault();
    }

    private long number(Object value) {
        if (value instanceof Number number) return Math.max(0L, number.longValue());
        return value == null ? 0L : parseLong(value.toString());
    }

    private long parseLong(String value) {
        try { return Math.max(0L, Long.parseLong(value)); } catch (NumberFormatException ignored) { return 0L; }
    }

    private BigDecimal decimal(Object value) {
        if (value instanceof BigDecimal decimal) return decimal.max(BigDecimal.ZERO);
        if (value == null) return BigDecimal.ZERO;
        try { return new BigDecimal(value.toString()).max(BigDecimal.ZERO); }
        catch (NumberFormatException ignored) { return BigDecimal.ZERO; }
    }

    private long percentile95(List<Long> sorted) {
        if (sorted == null || sorted.isEmpty()) return 0L;
        int index = Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * 0.95d) - 1);
        return Math.max(0L, sorted.get(index));
    }

    public record RuntimeAggregate(long sampleCount, long totalTokens, BigDecimal totalCost,
        long p95LatencyMs, long parseFailureCount, long fallbackCount) {
    }
}
