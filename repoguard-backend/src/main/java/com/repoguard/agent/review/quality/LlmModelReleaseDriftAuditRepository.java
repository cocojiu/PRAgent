package com.repoguard.agent.review.quality;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Persistence boundary for idempotent drift repair previews and outcomes. */
@Repository
public class LlmModelReleaseDriftAuditRepository {

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public LlmModelReleaseDriftAuditRepository(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    StoredAudit find(long tenantId, String operationKey) {
        List<StoredAudit> rows = jdbcTemplate.query("""
            select id, operation_key, preview_fingerprint, status, operator, before_json, after_json,
                   changed_release_count, changed_task_count, skipped_running_task_count, failure_code,
                   created_at, updated_at
              from llm_model_release_drift_audit
             where tenant_id = ? and operation_key = ?
            """, this::map, tenantId, operationKey);
        return rows == null || rows.isEmpty() ? null : rows.getFirst();
    }

    StoredAudit insertPreview(long tenantId, String operationKey, String fingerprint,
        String operator, Object preview) {
        jdbcTemplate.update("""
            insert into llm_model_release_drift_audit
                (tenant_id, operation_key, preview_fingerprint, status, operator, before_json, created_at, updated_at)
            values (?, ?, ?, 'PREVIEW', ?, ?, current_timestamp(6), current_timestamp(6))
            """, tenantId, operationKey, fingerprint, operator, json(preview));
        return find(tenantId, operationKey);
    }

    StoredAudit markRunning(long tenantId, String operationKey) {
        jdbcTemplate.update("""
            update llm_model_release_drift_audit
               set status = 'RUNNING', updated_at = current_timestamp(6)
             where tenant_id = ? and operation_key = ? and status = 'PREVIEW'
            """, tenantId, operationKey);
        return find(tenantId, operationKey);
    }

    StoredAudit complete(long tenantId, String operationKey, Object after,
        int changedReleases, int changedTasks, int skippedRunningTasks) {
        jdbcTemplate.update("""
            update llm_model_release_drift_audit
               set status = 'COMPLETED', after_json = ?, changed_release_count = ?, changed_task_count = ?,
                   skipped_running_task_count = ?, failure_code = null, updated_at = current_timestamp(6)
             where tenant_id = ? and operation_key = ? and status in ('PREVIEW', 'RUNNING')
            """, json(after), changedReleases, changedTasks, skippedRunningTasks, tenantId, operationKey);
        return find(tenantId, operationKey);
    }

    StoredAudit fail(long tenantId, String operationKey, String failureCode) {
        jdbcTemplate.update("""
            update llm_model_release_drift_audit
               set status = 'FAILED', failure_code = ?, updated_at = current_timestamp(6)
             where tenant_id = ? and operation_key = ? and status in ('PREVIEW', 'RUNNING')
            """, failureCode, tenantId, operationKey);
        return find(tenantId, operationKey);
    }

    private StoredAudit map(ResultSet rs, int rowNum) throws SQLException {
        return new StoredAudit(
            rs.getLong("id"), rs.getString("operation_key"), rs.getString("preview_fingerprint"),
            rs.getString("status"), rs.getString("operator"), rs.getString("before_json"),
            rs.getString("after_json"), rs.getInt("changed_release_count"), rs.getInt("changed_task_count"),
            rs.getInt("skipped_running_task_count"), rs.getString("failure_code"),
            time(rs, "created_at"), time(rs, "updated_at")
        );
    }

    private String json(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("发布漂移证据序列化失败", ex);
        }
    }

    private LocalDateTime time(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    record StoredAudit(Long id, String operationKey, String previewFingerprint, String status,
        String operator, String beforeJson, String afterJson, int changedReleaseCount,
        int changedTaskCount, int skippedRunningTaskCount, String failureCode,
        LocalDateTime createdAt, LocalDateTime updatedAt) { }
}
