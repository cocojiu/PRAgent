package com.repoguard.agent.review.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.config.JacksonConfig;
import com.repoguard.agent.dto.LlmModelReleaseDto;
import com.repoguard.agent.dto.LlmModelReleaseDriftDto;
import com.repoguard.agent.dto.LlmModelReleaseDriftRepairDto;
import com.repoguard.agent.dto.LlmModelReleaseDriftRepairRequest;
import com.repoguard.agent.tenancy.TenantContext;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class LlmModelReleaseDriftServiceTest {

    private static final String FINGERPRINT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private final JdbcTemplate jdbcTemplate = org.mockito.Mockito.mock(JdbcTemplate.class);
    private final LlmModelReleaseRepository releaseRepository = org.mockito.Mockito.mock(LlmModelReleaseRepository.class);
    private final LlmModelReleaseDriftAuditRepository auditRepository = org.mockito.Mockito.mock(LlmModelReleaseDriftAuditRepository.class);
    private final LlmModelReleaseDriftService service = new LlmModelReleaseDriftService(
        jdbcTemplate, new JacksonConfig().objectMapper(), releaseRepository, auditRepository);
    private TenantContext.Scope tenantScope;

    @BeforeEach
    void setTenant() {
        tenantScope = TenantContext.withTenant(42L);
        when(jdbcTemplate.queryForList(contains("from review_task"), any(Object[].class))).thenReturn(List.of());
    }

    @AfterEach
    void clearTenant() {
        tenantScope.close();
    }

    @Test
    void detectorFindsReleaseCardinalityTrafficAndAssignmentDrift() {
        LlmModelReleaseDto activeOld = release(1L, "active-old", "ACTIVE", 100, "gpt-old", 1);
        LlmModelReleaseDto activeNew = release(2L, "active-new", "ACTIVE", 95, "gpt-new", 2);
        LlmModelReleaseDto canary = release(3L, "canary", "CANARY", 0, "gpt-canary", 3);
        when(releaseRepository.findAll(42L)).thenReturn(List.of(activeOld, activeNew, canary));
        when(jdbcTemplate.queryForList(contains("from review_task"), any(Object[].class))).thenReturn(List.of(
            row(10L, "deleted-release", "openai", "gpt-old", "QUEUED", null, null),
            row(11L, "active-new", "openai", "wrong-model", "REVIEWING", LocalDateTime.now(), LocalDateTime.now())
        ));

        LlmModelReleaseDriftDto drift = service.detect();

        assertThat(drift.healthy()).isFalse();
        assertThat(drift.fingerprint()).hasSize(64);
        assertThat(drift.findings()).extracting(LlmModelReleaseDriftDto.FindingDto::code)
            .contains("MULTIPLE_ACTIVE_RELEASES", "RELEASE_TRAFFIC_STATE_MISMATCH",
                "ASSIGNMENT_RELEASE_MISSING", "ASSIGNMENT_METADATA_MISMATCH");
        assertThat(drift.releaseSummary().desiredActiveReleaseId()).isEqualTo(2L);
        assertThat(drift.assignmentSummary().runningTaskDriftCount()).isEqualTo(1);
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    @Test
    void repairRejectsStalePreviewAndRequiresExplicitConfirmation() {
        LlmModelReleaseDriftRepairRequest unconfirmed = new LlmModelReleaseDriftRepairRequest(FINGERPRINT, FINGERPRINT, false);
        assertThatThrownBy(() -> service.repair(unconfirmed, "operator", "ADMIN"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("显式确认");

        LlmModelReleaseDriftRepairRequest unauthorized = new LlmModelReleaseDriftRepairRequest(FINGERPRINT, FINGERPRINT, true);
        assertThatThrownBy(() -> service.repair(unauthorized, "operator", "VIEWER"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("发布管理员");

        when(releaseRepository.findAll(42L)).thenReturn(List.of());
        LlmModelReleaseDriftRepairRequest stale = new LlmModelReleaseDriftRepairRequest("op-1", FINGERPRINT, true);
        assertThatThrownBy(() -> service.repair(stale, "operator", "ADMIN"))
            .isInstanceOf(BusinessException.class).hasMessageContaining("重新生成");
        verify(auditRepository, never()).insertPreview(anyLong(), anyString(), anyString(), anyString(), any());
    }

    @Test
    void repairChangesOnlyQueuedAssignmentsAndLeavesRunningTaskUntouched() throws Exception {
        LlmModelReleaseDto active = release(2L, "active", "ACTIVE", 100, "gpt-active", 2);
        when(releaseRepository.findAll(42L)).thenReturn(List.of(active));
        Map<String, Object> queued = row(10L, "deleted", "openai", "gpt-old", "QUEUED", null, null);
        Map<String, Object> running = row(11L, "active", "openai", "wrong", "REVIEWING", LocalDateTime.now(), LocalDateTime.now());
        when(jdbcTemplate.queryForList(contains("from review_task"), any(Object[].class)))
            .thenReturn(List.of(queued, running));
        when(auditRepository.find(42L, "op-1")).thenReturn(null);
        LlmModelReleaseDriftDto preview = service.detect();
        LlmModelReleaseDriftRepairRequest request = new LlmModelReleaseDriftRepairRequest("op-1", preview.fingerprint(), true);
        LlmModelReleaseDriftAuditRepository.StoredAudit stored = stored("op-1", preview.fingerprint(), "COMPLETED", preview, preview);
        when(auditRepository.insertPreview(eq(42L), eq("op-1"), eq(preview.fingerprint()), eq("operator"), any()))
            .thenReturn(stored);
        when(auditRepository.markRunning(42L, "op-1")).thenReturn(stored);
        when(auditRepository.complete(eq(42L), eq("op-1"), any(), anyInt(), anyInt(), anyInt())).thenReturn(stored);
        when(releaseRepository.findByReleaseKey(42L, "deleted")).thenReturn(null);
        when(releaseRepository.findById(42L, 2L)).thenReturn(active);

        LlmModelReleaseDriftRepairDto result = service.repair(request, "operator", "ADMIN");

        assertThat(result.status()).isEqualTo("COMPLETED");
        verify(jdbcTemplate).update(contains("update review_task set llm_release_key"),
            eq(active.releaseKey()), eq(active.provider()), eq(active.modelName()), eq(42L), eq(10L));
        verify(jdbcTemplate, never()).update(contains("id = ?"), eq(42L), eq(11L));
    }

    @Test
    void completedOperationIsIdempotentAndDoesNotReinspectOrWrite() throws Exception {
        LlmModelReleaseDriftDto empty = new LlmModelReleaseDriftDto(
            LocalDateTime.now(), true, FINGERPRINT, List.of(),
            new LlmModelReleaseDriftDto.ReleaseSummary(0, 0, List.of(), List.of(), null, null),
            new LlmModelReleaseDriftDto.AssignmentSummary(0, 0, 0, 0, List.of()));
        when(auditRepository.find(42L, "op-done"))
            .thenReturn(stored("op-done", FINGERPRINT, "COMPLETED", empty, empty));

        LlmModelReleaseDriftRepairDto result = service.repair(
            new LlmModelReleaseDriftRepairRequest("op-done", FINGERPRINT, true), "operator", "ADMIN");

        assertThat(result.status()).isEqualTo("COMPLETED");
        verify(releaseRepository, never()).findAll(anyLong());
        verify(jdbcTemplate, never()).update(anyString(), any(Object[].class));
    }

    private LlmModelReleaseDriftAuditRepository.StoredAudit stored(String key, String fingerprint,
        String status, Object before, Object after) throws Exception {
        String beforeJson = new JacksonConfig().objectMapper().writeValueAsString(before);
        String afterJson = after == null ? null : new JacksonConfig().objectMapper().writeValueAsString(after);
        return new LlmModelReleaseDriftAuditRepository.StoredAudit(1L, key, fingerprint, status, "operator",
            beforeJson, afterJson, 0, 1, 1, null, LocalDateTime.now(), LocalDateTime.now());
    }

    private LlmModelReleaseDto release(Long id, String key, String state, int traffic, String model, int updatedDay) {
        return new LlmModelReleaseDto(id, key, "openai", model, "prompt", "context", "schema", "dataset", "v1",
            FINGERPRINT, state, traffic, true, new BigDecimal("0.95"), new BigDecimal("0.9"),
            new BigDecimal("0.98"), new BigDecimal("0.01"), new BigDecimal("0.01"), 1000L,
            new BigDecimal("0.01"), 1000L, List.of(), null, "operator",
            LocalDateTime.of(2026, 9, updatedDay, 0, 0), LocalDateTime.of(2026, 9, updatedDay, 0, 0), null);
    }

    private Map<String, Object> row(Long id, String releaseKey, String provider, String model,
        String status, LocalDateTime startedAt, LocalDateTime claimedAt) {
        Map<String, Object> row = new HashMap<>();
        row.put("id", id);
        row.put("llm_release_key", releaseKey);
        row.put("llm_provider", provider);
        row.put("llm_model", model);
        row.put("status", status);
        row.put("started_at", startedAt);
        row.put("review_claimed_at", claimedAt);
        return row;
    }
}
