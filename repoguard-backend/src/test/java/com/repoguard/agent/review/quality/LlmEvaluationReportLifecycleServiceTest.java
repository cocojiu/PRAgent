package com.repoguard.agent.review.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.dto.LlmModelReleaseRequest.LlmEvaluationReportLifecycleRequest;
import com.repoguard.agent.tenancy.TenantContext;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class LlmEvaluationReportLifecycleServiceTest {

    private static final long TENANT_ID = 42L;
    private static final long REPORT_ID = 77L;
    private static final LocalDateTime CREATED_AT = LocalDateTime.of(2026, 8, 1, 0, 0);
    private static final String FINGERPRINT = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private JdbcTemplate jdbcTemplate;
    private LlmModelReleaseRepository releaseRepository;
    private LlmEvaluationReportLifecycleService service;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        releaseRepository = mock(LlmModelReleaseRepository.class);
        service = new LlmEvaluationReportLifecycleService(jdbcTemplate, releaseRepository);
    }

    @Test
    void constructorClampsRetentionAndRejectsMissingDependencies() {
        assertThat(new LlmEvaluationReportLifecycleService(jdbcTemplate, releaseRepository, 10).defaultRetentionDays())
            .isEqualTo(180);
        assertThat(new LlmEvaluationReportLifecycleService(jdbcTemplate, releaseRepository, 4_000).defaultRetentionDays())
            .isEqualTo(3_650);
        assertThatThrownBy(() -> new LlmEvaluationReportLifecycleService(null, releaseRepository, 180))
            .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LlmEvaluationReportLifecycleService(jdbcTemplate, null, 180))
            .isInstanceOf(NullPointerException.class);
    }

    @Test
    void usableForNewReleaseRequiresCompletedActiveAuthorizedAndUnexpiredReport() {
        LocalDateTime now = LocalDateTime.of(2026, 9, 1, 0, 0);

        assertThat(service.usableForNewRelease(null, now)).isFalse();
        assertThat(service.usableForNewRelease(stored("FAILED", "ACTIVE", null), now)).isFalse();
        assertThat(service.usableForNewRelease(stored("COMPLETED", "FROZEN", null), now)).isFalse();
        assertThat(service.usableForNewRelease(stored("COMPLETED", "ACTIVE", now.plusDays(1)), now)).isTrue();
        assertThat(service.usableForNewRelease(stored("COMPLETED", "ACTIVE", now), now)).isFalse();
        assertThat(service.usableForNewRelease(storedWithAuthorizationRevoked(), now)).isFalse();
        assertThat(service.usableForNewRelease(
            new LlmModelReleaseRepository.StoredEvaluationReport(
                REPORT_ID, "report-key", "COMPLETED", "operator", null, evaluationReport(),
                "ACTIVE", 180, null, null, null, null, 0L
            ),
            null
        )).isTrue();
    }

    @Test
    void transitionValidatesIdentifiersAndRequiresDistinctApprovalForNonAdmins() {
        LlmEvaluationReportLifecycleRequest request = request("FREEZE", "key-1", "reason", null);

        assertThatThrownBy(() -> service.transition(0L, request, "operator", "ADMIN"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("参数不完整");
        assertThatThrownBy(() -> service.transition(REPORT_ID, null, "operator", "ADMIN"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("参数不完整");
        assertThatThrownBy(() -> service.transition(REPORT_ID, request(" ", "key-1", "reason", null), "operator", "ADMIN"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("操作不能为空");
        assertThatThrownBy(() -> service.transition(REPORT_ID, request("FREEZE", " ", "reason", null), "operator", "ADMIN"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("幂等键不能为空");
        assertThatThrownBy(() -> service.transition(REPORT_ID, request, "operator", "REVIEWER"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("二次审批");
        assertThatThrownBy(() -> service.transition(
            REPORT_ID, request("FREEZE", "key-1", "reason", " operator "), "operator", "reviewer"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("二次审批");
        verifyNoInteractions(jdbcTemplate, releaseRepository);
    }

    @Test
    void adminCanFreezeReportAndAuditIsWrittenWithNormalizedValues() {
        when(jdbcTemplate.query(contains("lifecycle_audit"), ArgumentMatchers.<RowMapper<Object>>any(), anyLong(), anyString()))
            .thenReturn(List.of());
        when(jdbcTemplate.queryForObject(contains("llm_model_release"), eq(Long.class), anyLong(), anyLong()))
            .thenReturn(0L);
        doReturn(1).when(jdbcTemplate).update(contains("update llm_evaluation_report"), any(Object[].class));
        when(releaseRepository.findEvaluationReport(TENANT_ID, REPORT_ID))
            .thenReturn(stored("COMPLETED", "ACTIVE", CREATED_AT.plusDays(30)), stored("COMPLETED", "FROZEN", CREATED_AT.plusDays(30)));

        try (TenantContext.Scope _ = TenantContext.withTenant(TENANT_ID)) {
            LlmModelReleaseRepository.StoredEvaluationReport result = service.transition(
                REPORT_ID,
                request(" freeze ", " key-freeze ", "  retention review  ", null),
                " " + "operator" + " ",
                "admin"
            );

            assertThat(result.lifecycleStatus()).isEqualTo("FROZEN");
        }
        verify(releaseRepository).lockTenant(TENANT_ID);
        verify(jdbcTemplate).update(contains("insert into llm_evaluation_report_lifecycle_audit"), any(Object[].class));
    }

    @Test
    void deleteReferencedReportFreezesItAndRecordsBlockedDeleteAction() {
        noAudit();
        when(jdbcTemplate.queryForObject(contains("llm_model_release"), eq(Long.class), anyLong(), anyLong()))
            .thenReturn(2L);
        doReturn(1).when(jdbcTemplate).update(contains("update llm_evaluation_report"), any(Object[].class));
        when(releaseRepository.findEvaluationReport(TENANT_ID, REPORT_ID))
            .thenReturn(stored("COMPLETED", "ACTIVE", CREATED_AT.plusDays(30)), stored("COMPLETED", "FROZEN", CREATED_AT.plusDays(30)));

        try (TenantContext.Scope _ = TenantContext.withTenant(TENANT_ID)) {
            assertThat(service.transition(REPORT_ID, request("DELETE", "key-delete", "privacy request", null), "admin", "PLATFORM_ADMIN")
                .lifecycleStatus()).isEqualTo("FROZEN");
        }
        verify(jdbcTemplate).update(contains("insert into llm_evaluation_report_lifecycle_audit"),
            eq(TENANT_ID), eq(REPORT_ID), eq("key-delete"), eq("DELETE_BLOCKED_FREEZE"), eq("ACTIVE"), eq("FROZEN"),
            eq("admin"), eq(null), eq("privacy request"));
    }

    @Test
    void revokeAuthorizationAndUnreferencedDeleteUseSeparateTerminalStates() {
        noAudit();
        when(jdbcTemplate.queryForObject(contains("llm_model_release"), eq(Long.class), anyLong(), anyLong()))
            .thenReturn(null, -1L);
        doReturn(1).when(jdbcTemplate).update(contains("update llm_evaluation_report"), any(Object[].class));
        when(releaseRepository.findEvaluationReport(TENANT_ID, REPORT_ID))
            .thenReturn(
                stored("COMPLETED", "ACTIVE", CREATED_AT.plusDays(30)),
                storedWithAuthorizationRevoked(),
                stored("COMPLETED", "ACTIVE", CREATED_AT.plusDays(30)),
                stored("COMPLETED", "DELETED", CREATED_AT.plusDays(30))
            );

        try (TenantContext.Scope _ = TenantContext.withTenant(TENANT_ID)) {
            assertThat(service.transition(REPORT_ID, request("REVOKE_AUTHORIZATION", "key-revoke", "consent withdrawn", null), "admin", "ADMIN")
                .lifecycleStatus()).isEqualTo("AUTHORIZATION_REVOKED");
            assertThat(service.transition(REPORT_ID, request("DELETE", "key-delete-2", "erase aggregate", null), "admin", "ADMIN")
                .lifecycleStatus()).isEqualTo("DELETED");
        }
    }

    @Test
    void idempotencyAndRetryPathsAvoidDuplicateStateChangesAndSurfaceConflicts() {
        ResultSet completedAudit = mock(ResultSet.class);
        try {
            when(completedAudit.getLong("report_id")).thenReturn(REPORT_ID);
            when(completedAudit.getString("status")).thenReturn("COMPLETED");
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        when(jdbcTemplate.query(contains("lifecycle_audit"), ArgumentMatchers.<RowMapper<Object>>any(), anyLong(), anyString()))
            .thenAnswer(invocation -> {
                RowMapper<Object> mapper = invocation.getArgument(1);
                return List.of(mapper.mapRow(completedAudit, 0));
            });
        LlmModelReleaseRepository.StoredEvaluationReport frozen = stored("COMPLETED", "FROZEN", CREATED_AT.plusDays(30));
        when(releaseRepository.findEvaluationReport(TENANT_ID, REPORT_ID)).thenReturn(frozen);

        try (TenantContext.Scope _ = TenantContext.withTenant(TENANT_ID)) {
            assertThat(service.transition(REPORT_ID, request("FREEZE", "key-idempotent", "repeat", null), "admin", "ADMIN"))
                .isSameAs(frozen);
        }
        verify(releaseRepository).findEvaluationReport(TENANT_ID, REPORT_ID);
        verify(releaseRepository, never()).lockTenant(anyLong());
    }

    @Test
    void completedIdempotencyKeyCannotBeReplayedForAnotherReport() {
        ResultSet completedAudit = mock(ResultSet.class);
        try {
            when(completedAudit.getLong("report_id")).thenReturn(999L);
            when(completedAudit.getString("status")).thenReturn("COMPLETED");
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        when(jdbcTemplate.query(contains("lifecycle_audit"), ArgumentMatchers.<RowMapper<Object>>any(), anyLong(), anyString()))
            .thenAnswer(invocation -> {
                RowMapper<Object> mapper = invocation.getArgument(1);
                return List.of(mapper.mapRow(completedAudit, 0));
            });

        try (TenantContext.Scope _ = TenantContext.withTenant(TENANT_ID)) {
            assertThatThrownBy(() -> service.transition(
                REPORT_ID, request("FREEZE", "cross-report-key", "retry", null), "admin", "ADMIN"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("其他评估报告");
        }
        verifyNoInteractions(releaseRepository);
    }

    @Test
    void sameStateAndFailedAuditRetryAreSafeAndIllegalStatesAreRejected() {
        reset(jdbcTemplate, releaseRepository);
        noAudit();
        when(jdbcTemplate.queryForObject(contains("llm_model_release"), eq(Long.class), anyLong(), anyLong())).thenReturn(0L);
        when(releaseRepository.findEvaluationReport(TENANT_ID, REPORT_ID)).thenReturn(
            stored("COMPLETED", "FROZEN", CREATED_AT.plusDays(30)),
            stored("COMPLETED", "FROZEN", CREATED_AT.plusDays(30))
        );
        try (TenantContext.Scope _ = TenantContext.withTenant(TENANT_ID)) {
            assertThat(service.transition(REPORT_ID, request("FREEZE", "key-same", "same", null), "admin", "ADMIN")
                .lifecycleStatus()).isEqualTo("FROZEN");
        }

        reset(jdbcTemplate, releaseRepository);
        ResultSet failedAudit = mock(ResultSet.class);
        try {
            when(failedAudit.getLong("report_id")).thenReturn(REPORT_ID);
            when(failedAudit.getString("status")).thenReturn("FAILED");
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        when(jdbcTemplate.query(contains("lifecycle_audit"), ArgumentMatchers.<RowMapper<Object>>any(), anyLong(), anyString()))
            .thenAnswer(invocation -> {
                RowMapper<Object> mapper = invocation.getArgument(1);
                return List.of(mapper.mapRow(failedAudit, 0));
            });
        when(jdbcTemplate.queryForObject(contains("llm_model_release"), eq(Long.class), anyLong(), anyLong())).thenReturn(0L);
        doReturn(1).when(jdbcTemplate).update(contains("update llm_evaluation_report"), any(Object[].class));
        when(releaseRepository.findEvaluationReport(TENANT_ID, REPORT_ID)).thenReturn(
            stored("COMPLETED", "ACTIVE", CREATED_AT.plusDays(30)), stored("COMPLETED", "FROZEN", CREATED_AT.plusDays(30))
        );
        try (TenantContext.Scope _ = TenantContext.withTenant(TENANT_ID)) {
            assertThat(service.transition(REPORT_ID, request("FREEZE", "key-failed", "retry", null), "admin", "ADMIN")
                .lifecycleStatus()).isEqualTo("FROZEN");
        }
        verify(jdbcTemplate).update(contains("update llm_evaluation_report_lifecycle_audit"), any(Object[].class));

        reset(jdbcTemplate, releaseRepository);
        noAudit();
        when(jdbcTemplate.queryForObject(contains("llm_model_release"), eq(Long.class), anyLong(), anyLong())).thenReturn(0L);
        doReturn(0).when(jdbcTemplate).update(contains("update llm_evaluation_report"), any(Object[].class));
        when(releaseRepository.findEvaluationReport(TENANT_ID, REPORT_ID)).thenReturn(
            stored("COMPLETED", "ACTIVE", CREATED_AT.plusDays(30)), stored("COMPLETED", "ACTIVE", CREATED_AT.plusDays(30))
        );
        try (TenantContext.Scope _ = TenantContext.withTenant(TENANT_ID)) {
            assertThatThrownBy(() -> service.transition(REPORT_ID, request("FREEZE", "key-conflict", "race", null), "admin", "ADMIN"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("重新读取");
        }

        reset(jdbcTemplate, releaseRepository);
        noAudit();
        when(jdbcTemplate.queryForObject(contains("llm_model_release"), eq(Long.class), anyLong(), anyLong())).thenReturn(0L);
        when(releaseRepository.findEvaluationReport(TENANT_ID, REPORT_ID)).thenReturn(
            stored("COMPLETED", "DELETED", CREATED_AT.plusDays(30))
        );
        try (TenantContext.Scope _ = TenantContext.withTenant(TENANT_ID)) {
            assertThatThrownBy(() -> service.transition(REPORT_ID, request("FREEZE", "key-deleted", "retry", null), "admin", "ADMIN"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("软删除");
        }

        reset(jdbcTemplate, releaseRepository);
        noAudit();
        when(jdbcTemplate.queryForObject(contains("llm_model_release"), eq(Long.class), anyLong(), anyLong())).thenReturn(0L);
        when(releaseRepository.findEvaluationReport(TENANT_ID, REPORT_ID)).thenReturn(
            stored("COMPLETED", "FROZEN", CREATED_AT.plusDays(30))
        );
        try (TenantContext.Scope _ = TenantContext.withTenant(TENANT_ID)) {
            assertThatThrownBy(() -> service.transition(REPORT_ID, request("REVOKE_AUTHORIZATION", "key-frozen", "retry", null), "admin", "ADMIN"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("冻结");
        }

        reset(jdbcTemplate, releaseRepository);
        noAudit();
        when(jdbcTemplate.queryForObject(contains("llm_model_release"), eq(Long.class), anyLong(), anyLong())).thenReturn(0L);
        when(releaseRepository.findEvaluationReport(TENANT_ID, REPORT_ID)).thenReturn(
            stored("COMPLETED", "UNKNOWN", CREATED_AT.plusDays(30))
        );
        try (TenantContext.Scope _ = TenantContext.withTenant(TENANT_ID)) {
            assertThatThrownBy(() -> service.transition(REPORT_ID, request("FREEZE", "key-unknown", "retry", null), "admin", "ADMIN"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不允许");
        }
    }

    @Test
    void expirySweepExpiresUnreferencedReportsFreezesReferencedReportsAndSkipsRetries() {
        when(jdbcTemplate.query(contains("select id from llm_evaluation_report"), ArgumentMatchers.<RowMapper<Long>>any(),
            anyLong(), any(LocalDateTime.class), any(LocalDateTime.class), anyInt()))
            .thenReturn(Arrays.asList(null, 0L, 77L, 78L, 79L, 80L));
        when(releaseRepository.findEvaluationReport(TENANT_ID, 77L)).thenReturn(stored("COMPLETED", "ACTIVE", CREATED_AT));
        when(releaseRepository.findEvaluationReport(TENANT_ID, 78L)).thenReturn(stored("COMPLETED", "ACTIVE", CREATED_AT));
        when(releaseRepository.findEvaluationReport(TENANT_ID, 79L)).thenReturn(stored("COMPLETED", "FROZEN", CREATED_AT));
        when(releaseRepository.findEvaluationReport(TENANT_ID, 80L)).thenReturn(stored("COMPLETED", "ACTIVE", CREATED_AT));
        when(jdbcTemplate.queryForObject(contains("llm_model_release"), eq(Long.class), anyLong(), anyLong()))
            .thenReturn(0L, 1L, 0L);
        when(jdbcTemplate.query(contains("lifecycle_audit"), ArgumentMatchers.<RowMapper<Object>>any(), anyLong(), anyString()))
            .thenAnswer(invocation -> {
                String operationKey = invocation.getArgument(3);
                if (operationKey.contains(":80:")) {
                    ResultSet rs = mock(ResultSet.class);
                    when(rs.getLong("report_id")).thenReturn(80L);
                    when(rs.getString("status")).thenReturn("COMPLETED");
                    RowMapper<Object> mapper = invocation.getArgument(1);
                    return List.of(mapper.mapRow(rs, 0));
                }
                return List.of();
            });
        doReturn(1).when(jdbcTemplate).update(contains("update llm_evaluation_report"), any(Object[].class));

        try (TenantContext.Scope _ = TenantContext.withTenant(TENANT_ID)) {
            assertThat(service.expireDueReports()).isEqualTo(2);
        }
        verify(releaseRepository).lockTenant(TENANT_ID);
        verify(jdbcTemplate, org.mockito.Mockito.times(2)).update(contains("update llm_evaluation_report"), any(Object[].class));
    }

    @Test
    void expirySweepDoesNotAuditAConcurrentTransitionThatDidNotUpdate() {
        when(jdbcTemplate.query(contains("select id from llm_evaluation_report"), ArgumentMatchers.<RowMapper<Long>>any(),
            anyLong(), any(LocalDateTime.class), any(LocalDateTime.class), anyInt()))
            .thenReturn(List.of(REPORT_ID));
        when(releaseRepository.findEvaluationReport(TENANT_ID, REPORT_ID)).thenReturn(
            stored("COMPLETED", "ACTIVE", CREATED_AT), stored("COMPLETED", "ACTIVE", CREATED_AT));
        when(jdbcTemplate.queryForObject(contains("llm_model_release"), eq(Long.class), anyLong(), anyLong()))
            .thenReturn(0L);
        noAudit();
        doReturn(0).when(jdbcTemplate).update(contains("update llm_evaluation_report"), any(Object[].class));

        try (TenantContext.Scope _ = TenantContext.withTenant(TENANT_ID)) {
            assertThatThrownBy(service::expireDueReports)
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("其他操作改变");
        }
        verify(jdbcTemplate, never()).update(contains("insert into llm_evaluation_report_lifecycle_audit"), any(Object[].class));
    }

    @Test
    void exportAuthorizationIsAdminOnlyAndAuditedIdempotently() {
        assertThatThrownBy(() -> service.authorizeExport(REPORT_ID, "json", "operator", "REVIEWER"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("管理员授权");

        when(releaseRepository.findEvaluationReport(TENANT_ID, REPORT_ID)).thenReturn(
            stored("COMPLETED", "DELETED", CREATED_AT.plusDays(30))
        );
        try (TenantContext.Scope _ = TenantContext.withTenant(TENANT_ID)) {
            assertThatThrownBy(() -> service.authorizeExport(REPORT_ID, "json", "operator", "ADMIN"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("软删除");
        }

        reset(jdbcTemplate, releaseRepository);
        noAudit();
        when(releaseRepository.findEvaluationReport(TENANT_ID, REPORT_ID)).thenReturn(
            stored("COMPLETED", "ACTIVE", CREATED_AT.plusDays(30))
        );
        try (TenantContext.Scope _ = TenantContext.withTenant(TENANT_ID)) {
            assertThat(service.authorizeExport(REPORT_ID, "HTML", null, "platform_admin").lifecycleStatus())
                .isEqualTo("ACTIVE");
        }
        verify(jdbcTemplate).update(contains("insert into llm_evaluation_report_lifecycle_audit"), any(Object[].class));

        reset(jdbcTemplate, releaseRepository);
        ResultSet failedAudit = mock(ResultSet.class);
        try {
            when(failedAudit.getLong("report_id")).thenReturn(REPORT_ID);
            when(failedAudit.getString("status")).thenReturn("FAILED");
        } catch (Exception exception) {
            throw new AssertionError(exception);
        }
        when(jdbcTemplate.query(contains("lifecycle_audit"), ArgumentMatchers.<RowMapper<Object>>any(), anyLong(), anyString()))
            .thenAnswer(invocation -> {
                RowMapper<Object> mapper = invocation.getArgument(1);
                return List.of(mapper.mapRow(failedAudit, 0));
            });
        when(releaseRepository.findEvaluationReport(TENANT_ID, REPORT_ID)).thenReturn(
            stored("COMPLETED", "ACTIVE", CREATED_AT.plusDays(30))
        );
        try (TenantContext.Scope _ = TenantContext.withTenant(TENANT_ID)) {
            assertThat(service.authorizeExport(REPORT_ID, null, "operator", "ADMIN").lifecycleStatus())
                .isEqualTo("ACTIVE");
        }
        verify(jdbcTemplate).update(contains("update llm_evaluation_report_lifecycle_audit"), any(Object[].class));
    }

    @Test
    void duplicateAuditInsertDoesNotBreakSuccessfulLifecycleTransition() {
        noAudit();
        when(jdbcTemplate.queryForObject(contains("llm_model_release"), eq(Long.class), anyLong(), anyLong())).thenReturn(0L);
        doReturn(1).when(jdbcTemplate).update(contains("update llm_evaluation_report"), any(Object[].class));
        doThrow(new DuplicateKeyException("concurrent retry"))
            .when(jdbcTemplate).update(contains("insert into llm_evaluation_report_lifecycle_audit"), any(Object[].class));
        when(releaseRepository.findEvaluationReport(TENANT_ID, REPORT_ID)).thenReturn(
            stored("COMPLETED", "ACTIVE", CREATED_AT.plusDays(30)), stored("COMPLETED", "FROZEN", CREATED_AT.plusDays(30))
        );

        try (TenantContext.Scope _ = TenantContext.withTenant(TENANT_ID)) {
            assertThat(service.transition(REPORT_ID, request("FREEZE", "key-duplicate", "retry safe", null), "admin", "ADMIN")
                .lifecycleStatus()).isEqualTo("FROZEN");
        }
    }

    private void noAudit() {
        when(jdbcTemplate.query(contains("lifecycle_audit"), ArgumentMatchers.<RowMapper<Object>>any(), anyLong(), anyString()))
            .thenReturn(List.of());
    }

    private LlmEvaluationReportLifecycleRequest request(String action, String key, String reason, String secondApprover) {
        return new LlmEvaluationReportLifecycleRequest(action, reason, secondApprover, key);
    }

    private LlmModelReleaseRepository.StoredEvaluationReport stored(String status, String lifecycleStatus, LocalDateTime expiresAt) {
        return new LlmModelReleaseRepository.StoredEvaluationReport(
            REPORT_ID, "report-key", status, "operator", CREATED_AT, evaluationReport(), lifecycleStatus,
            180, expiresAt, null, "FROZEN".equals(lifecycleStatus) ? CREATED_AT : null,
            "DELETED".equals(lifecycleStatus) ? CREATED_AT : null, 0L
        );
    }

    private LlmModelReleaseRepository.StoredEvaluationReport storedWithAuthorizationRevoked() {
        return new LlmModelReleaseRepository.StoredEvaluationReport(
            REPORT_ID, "report-key", "COMPLETED", "operator", CREATED_AT, evaluationReport(), "AUTHORIZATION_REVOKED",
            180, CREATED_AT.plusDays(30), CREATED_AT, null, null, 0L
        );
    }

    private LlmEvaluationReport evaluationReport() {
        return new LlmEvaluationReport(
            new LlmEvaluationVersion("openai", "gpt-next", "prompt-v1", "context-v1", "schema-v1", "chunk-v1"),
            FINGERPRINT, 50, 10, 10, 10, 0, 0,
            new BigDecimal("0.95"), new BigDecimal("0.90"), new BigDecimal("0.85"),
            new BigDecimal("0.98"), new BigDecimal("0.01"), new BigDecimal("0.01"),
            java.util.Map.of(), 1000L, 1000L, new BigDecimal("0.01"), List.of(), true
        );
    }
}
