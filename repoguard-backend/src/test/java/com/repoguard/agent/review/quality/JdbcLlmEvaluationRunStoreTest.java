package com.repoguard.agent.review.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class JdbcLlmEvaluationRunStoreTest {

    @Test
    void findByRunIdMapsThePersistedAggregate() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JdbcLlmEvaluationRunStore store = new JdbcLlmEvaluationRunStore(jdbcTemplate);
        ResultSet resultSet = persistedRow();
        when(jdbcTemplate.query(
            contains("where tenant_id = ? and run_id = ?"),
            org.mockito.ArgumentMatchers.<RowMapper<LlmEvaluationRunStore.StoredRun>>any(),
            eq(new Object[] {42L, "run-1"})
        )).thenAnswer(invocation -> List.of(
            invocation.<RowMapper<LlmEvaluationRunStore.StoredRun>>getArgument(1).mapRow(resultSet, 0)
        ));

        LlmEvaluationRunStore.StoredRun stored = store.findByRunId(42L, "run-1");

        assertThat(stored.tenantId()).isEqualTo(42L);
        assertThat(stored.runKey()).isEqualTo("key-1");
        assertThat(stored.maxCost()).isEqualByComparingTo("3.25000000");
        assertThat(stored.reportId()).isNull();
        assertThat(stored.startedAt()).isEqualTo(LocalDateTime.of(2026, 9, 9, 12, 1));
        assertThat(stored.finishedAt()).isNull();
    }

    @Test
    void createOrGetReturnsTheExistingRunAfterAnIdempotencyConflict() throws Exception {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JdbcLlmEvaluationRunStore store = new JdbcLlmEvaluationRunStore(jdbcTemplate);
        LlmEvaluationRunStore.StoredRun candidate = candidate();
        when(jdbcTemplate.update(contains("insert into llm_evaluation_run"), any(Object[].class)))
            .thenThrow(new DuplicateKeyException("duplicate"));
        ResultSet resultSet = persistedRow();
        when(jdbcTemplate.query(
            contains("where tenant_id = ? and run_key = ?"),
            org.mockito.ArgumentMatchers.<RowMapper<LlmEvaluationRunStore.StoredRun>>any(),
            eq(new Object[] {42L, "key-1"})
        )).thenAnswer(invocation -> List.of(
            invocation.<RowMapper<LlmEvaluationRunStore.StoredRun>>getArgument(1).mapRow(resultSet, 0)
        ));

        assertThat(store.createOrGet(candidate)).isEqualTo(candidate);
    }

    @Test
    void saveAndRecoveryRequireDurableUpdates() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        JdbcLlmEvaluationRunStore store = new JdbcLlmEvaluationRunStore(jdbcTemplate);
        when(jdbcTemplate.update(
            contains("where tenant_id = ? and run_id = ?"),
            any(Object[].class)
        )).thenReturn(1, 0);
        LocalDateTime recoveredAt = LocalDateTime.of(2026, 9, 9, 13, 0);
        when(jdbcTemplate.update(contains("RUN_INTERRUPTED"), eq(new Object[] {recoveredAt, recoveredAt})))
            .thenReturn(2);

        store.save(candidate());
        assertThatThrownBy(() -> store.save(candidate()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("持久化失败");
        assertThat(store.recoverInterrupted(recoveredAt)).isEqualTo(2);
    }

    private LlmEvaluationRunStore.StoredRun candidate() {
        return new LlmEvaluationRunStore.StoredRun(
            42L,
            "run-1",
            "key-1",
            "RUNNING",
            "evaluation-data",
            2,
            20_000L,
            new BigDecimal("3.25000000"),
            300,
            "admin",
            20,
            4,
            1_200L,
            new BigDecimal("0.12500000"),
            null,
            null,
            LocalDateTime.of(2026, 9, 9, 12, 0),
            LocalDateTime.of(2026, 9, 9, 12, 1),
            null
        );
    }

    private ResultSet persistedRow() throws Exception {
        ResultSet row = mock(ResultSet.class);
        LlmEvaluationRunStore.StoredRun candidate = candidate();
        when(row.getLong("tenant_id")).thenReturn(candidate.tenantId());
        when(row.getString("run_id")).thenReturn(candidate.runId());
        when(row.getString("run_key")).thenReturn(candidate.runKey());
        when(row.getString("status")).thenReturn(candidate.status());
        when(row.getString("data_directory")).thenReturn(candidate.dataDirectory());
        when(row.getInt("max_concurrency")).thenReturn(candidate.maxConcurrency());
        when(row.getLong("max_tokens")).thenReturn(candidate.maxTokens());
        when(row.getBigDecimal("max_cost")).thenReturn(candidate.maxCost());
        when(row.getInt("max_duration_seconds")).thenReturn(candidate.maxDurationSeconds());
        when(row.getString("operator")).thenReturn(candidate.operator());
        when(row.getInt("total_samples")).thenReturn(candidate.totalSamples());
        when(row.getInt("completed_samples")).thenReturn(candidate.completedSamples());
        when(row.getLong("total_tokens")).thenReturn(candidate.totalTokens());
        when(row.getBigDecimal("total_cost")).thenReturn(candidate.totalCost());
        when(row.getObject("report_id")).thenReturn(candidate.reportId());
        when(row.getString("failure_code")).thenReturn(candidate.failureCode());
        when(row.getTimestamp("submitted_at")).thenReturn(Timestamp.valueOf(candidate.submittedAt()));
        when(row.getTimestamp("started_at")).thenReturn(Timestamp.valueOf(candidate.startedAt()));
        when(row.getTimestamp("finished_at")).thenReturn(null);
        return row;
    }
}
