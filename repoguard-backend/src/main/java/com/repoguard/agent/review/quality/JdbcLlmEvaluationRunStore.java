package com.repoguard.agent.review.quality;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
class JdbcLlmEvaluationRunStore implements LlmEvaluationRunStore {

    private final JdbcTemplate jdbcTemplate;

    JdbcLlmEvaluationRunStore(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public StoredRun createOrGet(StoredRun candidate) {
        try {
            jdbcTemplate.update("""
                insert into llm_evaluation_run (
                    tenant_id, run_id, run_key, status, data_directory, max_concurrency,
                    max_tokens, max_cost, max_duration_seconds, operator, total_samples,
                    completed_samples, total_tokens, total_cost, report_id, failure_code,
                    submitted_at, started_at, finished_at, created_at, updated_at
                ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, current_timestamp(6), current_timestamp(6))
                """,
                candidate.tenantId(),
                candidate.runId(),
                candidate.runKey(),
                candidate.status(),
                candidate.dataDirectory(),
                candidate.maxConcurrency(),
                candidate.maxTokens(),
                candidate.maxCost(),
                candidate.maxDurationSeconds(),
                candidate.operator(),
                candidate.totalSamples(),
                candidate.completedSamples(),
                candidate.totalTokens(),
                candidate.totalCost(),
                candidate.reportId(),
                candidate.failureCode(),
                candidate.submittedAt(),
                candidate.startedAt(),
                candidate.finishedAt()
            );
            return candidate;
        } catch (DuplicateKeyException ignored) {
            StoredRun existing = findByRunKey(candidate.tenantId(), candidate.runKey());
            if (existing == null) {
                throw ignored;
            }
            return existing;
        }
    }

    @Override
    public StoredRun findByRunId(long tenantId, String runId) {
        List<StoredRun> rows = jdbcTemplate.query("""
            select tenant_id, run_id, run_key, status, data_directory, max_concurrency,
                   max_tokens, max_cost, max_duration_seconds, operator, total_samples,
                   completed_samples, total_tokens, total_cost, report_id, failure_code,
                   submitted_at, started_at, finished_at
              from llm_evaluation_run
             where tenant_id = ? and run_id = ?
            """, this::map, tenantId, runId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    @Override
    public void save(StoredRun run) {
        int updated = jdbcTemplate.update("""
            update llm_evaluation_run
               set status = ?, total_samples = ?, completed_samples = ?, total_tokens = ?,
                   total_cost = ?, report_id = ?, failure_code = ?, started_at = ?,
                   finished_at = ?, updated_at = current_timestamp(6)
             where tenant_id = ? and run_id = ?
            """,
            run.status(),
            run.totalSamples(),
            run.completedSamples(),
            run.totalTokens(),
            run.totalCost(),
            run.reportId(),
            run.failureCode(),
            run.startedAt(),
            run.finishedAt(),
            run.tenantId(),
            run.runId()
        );
        if (updated != 1) {
            throw new IllegalStateException("评估运行状态持久化失败");
        }
    }

    @Override
    public int recoverInterrupted(LocalDateTime recoveredAt) {
        return jdbcTemplate.update("""
            update llm_evaluation_run
               set status = 'FAILED', failure_code = 'RUN_INTERRUPTED',
                   finished_at = ?, updated_at = current_timestamp(6)
             where status in ('QUEUED', 'RUNNING')
               and timestampadd(
                   second,
                   max_duration_seconds,
                   coalesce(started_at, submitted_at)
               ) <= ?
            """, recoveredAt, recoveredAt);
    }

    private StoredRun findByRunKey(long tenantId, String runKey) {
        List<StoredRun> rows = jdbcTemplate.query("""
            select tenant_id, run_id, run_key, status, data_directory, max_concurrency,
                   max_tokens, max_cost, max_duration_seconds, operator, total_samples,
                   completed_samples, total_tokens, total_cost, report_id, failure_code,
                   submitted_at, started_at, finished_at
              from llm_evaluation_run
             where tenant_id = ? and run_key = ?
            """, this::map, tenantId, runKey);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private StoredRun map(ResultSet rs, int rowNumber) throws SQLException {
        return new StoredRun(
            rs.getLong("tenant_id"),
            rs.getString("run_id"),
            rs.getString("run_key"),
            rs.getString("status"),
            rs.getString("data_directory"),
            rs.getInt("max_concurrency"),
            rs.getLong("max_tokens"),
            rs.getBigDecimal("max_cost"),
            rs.getInt("max_duration_seconds"),
            rs.getString("operator"),
            rs.getInt("total_samples"),
            rs.getInt("completed_samples"),
            rs.getLong("total_tokens"),
            rs.getBigDecimal("total_cost"),
            nullableLong(rs, "report_id"),
            rs.getString("failure_code"),
            time(rs, "submitted_at"),
            time(rs, "started_at"),
            time(rs, "finished_at")
        );
    }

    private Long nullableLong(ResultSet rs, String column) throws SQLException {
        Object value = rs.getObject(column);
        return value == null ? null : rs.getLong(column);
    }

    private LocalDateTime time(ResultSet rs, String column) throws SQLException {
        java.sql.Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
