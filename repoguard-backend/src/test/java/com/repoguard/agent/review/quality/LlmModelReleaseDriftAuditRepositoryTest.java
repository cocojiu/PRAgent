package com.repoguard.agent.review.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.config.JacksonConfig;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class LlmModelReleaseDriftAuditRepositoryTest {

    private static final String FINGERPRINT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final LlmModelReleaseDriftAuditRepository repository = new LlmModelReleaseDriftAuditRepository(
        jdbcTemplate, new JacksonConfig().objectMapper());

    @Test
    void findsAndMapsTenantScopedAudit() throws Exception {
        ResultSet resultSet = row();
        when(jdbcTemplate.query(contains("operation_key = ?"),
            org.mockito.ArgumentMatchers.<RowMapper<LlmModelReleaseDriftAuditRepository.StoredAudit>>any(),
            eq(42L), eq("operation-1"))).thenAnswer(invocation -> List.of(
                invocation.<RowMapper<LlmModelReleaseDriftAuditRepository.StoredAudit>>getArgument(1).mapRow(resultSet, 0)));

        LlmModelReleaseDriftAuditRepository.StoredAudit audit = repository.find(42L, "operation-1");

        assertThat(audit.status()).isEqualTo("COMPLETED");
        assertThat(audit.previewFingerprint()).isEqualTo(FINGERPRINT);
        assertThat(audit.changedTaskCount()).isEqualTo(2);
    }

    @Test
    void writesPreviewAndTerminalStatesWithBoundedEvidence() {
        repository.insertPreview(42L, "operation-1", FINGERPRINT, "operator", java.util.Map.of("healthy", false));
        repository.markRunning(42L, "operation-1");
        repository.complete(42L, "operation-1", java.util.Map.of("healthy", true), 1, 2, 3);
        repository.fail(42L, "operation-2", "DATA_ACCESS_EXCEPTION");

        verify(jdbcTemplate).update(contains("insert into llm_model_release_drift_audit"), eq(42L),
            eq("operation-1"), eq(FINGERPRINT), eq("operator"), anyString());
        verify(jdbcTemplate).update(contains("set status = 'RUNNING'"), eq(42L), eq("operation-1"));
        verify(jdbcTemplate).update(contains("set status = 'COMPLETED'"), anyString(), eq(1), eq(2), eq(3), eq(42L), eq("operation-1"));
        verify(jdbcTemplate).update(contains("set status = 'FAILED'"), eq("DATA_ACCESS_EXCEPTION"), eq(42L), eq("operation-2"));
    }

    private ResultSet row() throws Exception {
        ResultSet resultSet = mock(ResultSet.class);
        when(resultSet.getLong("id")).thenReturn(1L);
        when(resultSet.getString("operation_key")).thenReturn("operation-1");
        when(resultSet.getString("preview_fingerprint")).thenReturn(FINGERPRINT);
        when(resultSet.getString("status")).thenReturn("COMPLETED");
        when(resultSet.getString("operator")).thenReturn("operator");
        when(resultSet.getString("before_json")).thenReturn("{}");
        when(resultSet.getString("after_json")).thenReturn("{}");
        when(resultSet.getInt("changed_release_count")).thenReturn(1);
        when(resultSet.getInt("changed_task_count")).thenReturn(2);
        when(resultSet.getInt("skipped_running_task_count")).thenReturn(3);
        when(resultSet.getString("failure_code")).thenReturn(null);
        when(resultSet.getTimestamp("created_at")).thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 9, 3, 0, 0)));
        when(resultSet.getTimestamp("updated_at")).thenReturn(Timestamp.valueOf(LocalDateTime.of(2026, 9, 3, 1, 0)));
        return resultSet;
    }
}
