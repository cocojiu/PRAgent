package com.repoguard.agent.review.quality;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.LlmModelReleaseRequest.LlmEvaluationReportLifecycleRequest;
import com.repoguard.agent.tenancy.TenantContext;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Governs the retention and privacy lifecycle of aggregate-only evaluation reports.
 *
 * <p>The service intentionally uses soft deletion. A report that is referenced by a model
 * release is frozen instead of removed, so historical release evidence remains traceable.
 */
@Component
public class LlmEvaluationReportLifecycleService {

    static final int DEFAULT_RETENTION_DAYS = 180;
    private static final int MIN_RETENTION_DAYS = 30;
    private static final int MAX_RETENTION_DAYS = 3_650;
    private static final int EXPIRY_BATCH_SIZE = 100;

    private final JdbcTemplate jdbcTemplate;
    private final LlmModelReleaseRepository releaseRepository;
    private final int configuredRetentionDays;

    @Autowired
    public LlmEvaluationReportLifecycleService(
        JdbcTemplate jdbcTemplate,
        LlmModelReleaseRepository releaseRepository,
        @Value("${repoguard.review.evaluation-report-retention-days:180}") int configuredRetentionDays
    ) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate");
        this.releaseRepository = Objects.requireNonNull(releaseRepository, "releaseRepository");
        this.configuredRetentionDays = normalizeRetentionDays(configuredRetentionDays);
    }

    public LlmEvaluationReportLifecycleService(
        JdbcTemplate jdbcTemplate,
        LlmModelReleaseRepository releaseRepository
    ) {
        this(jdbcTemplate, releaseRepository, DEFAULT_RETENTION_DAYS);
    }

    public int defaultRetentionDays() {
        return configuredRetentionDays;
    }

    public boolean usableForNewRelease(
        LlmModelReleaseRepository.StoredEvaluationReport report,
        LocalDateTime now
    ) {
        if (report == null || !"COMPLETED".equalsIgnoreCase(report.status())) return false;
        if (!"ACTIVE".equalsIgnoreCase(report.lifecycleStatus())) return false;
        if (report.authorizationRevokedAt() != null) return false;
        LocalDateTime effectiveExpiry = report.effectiveExpiresAt();
        return effectiveExpiry == null || effectiveExpiry.isAfter(now == null ? LocalDateTime.now() : now);
    }

    @Transactional
    public LlmModelReleaseRepository.StoredEvaluationReport transition(
        long reportId,
        LlmEvaluationReportLifecycleRequest request,
        String operator,
        String role
    ) {
        return transition(TenantContext.currentTenantIdOrDefault(), reportId, request, operator, role);
    }

    @Transactional
    LlmModelReleaseRepository.StoredEvaluationReport transition(
        long tenantId,
        long reportId,
        LlmEvaluationReportLifecycleRequest request,
        String operator,
        String role
    ) {
        if (reportId < 1 || request == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "评估报告生命周期参数不完整");
        }
        String action = normalizeAction(request.action());
        String normalizedOperator = normalize(operator, "system", 128);
        String operationKey = normalize(request.idempotencyKey(), null, 128);
        if (operationKey == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "幂等键不能为空");
        }
        String reason = normalize(request.reason(), "生命周期操作", 512);
        String secondApprover = normalize(request.secondApprover(), null, 128);
        requireApproval(normalizedOperator, secondApprover, role);

        LifecycleAudit previous = findAudit(tenantId, operationKey);
        if (previous != null && "COMPLETED".equalsIgnoreCase(previous.status())) {
            if (!Objects.equals(previous.reportId(), reportId)) {
                throw new BusinessException(ErrorCode.CONFLICT, "幂等键已绑定其他评估报告");
            }
            return releaseRepository.findEvaluationReport(tenantId, reportId);
        }

        releaseRepository.lockTenant(tenantId);
        LlmModelReleaseRepository.StoredEvaluationReport report = releaseRepository.findEvaluationReport(tenantId, reportId);
        String current = normalizeLifecycleStatus(report.lifecycleStatus());
        long references = releaseReferenceCount(tenantId, reportId);
        String target = targetStatus(action, current, references > 0);
        String recordedAction = action;
        if ("DELETE".equals(action) && references > 0) {
            recordedAction = "DELETE_BLOCKED_FREEZE";
        }

        if (!current.equals(target)) {
            ensureTransitionAllowed(action, current);
            int updated = updateLifecycle(tenantId, reportId, current, target, action, reason);
            if (updated != 1) {
                LlmModelReleaseRepository.StoredEvaluationReport latest = releaseRepository.findEvaluationReport(tenantId, reportId);
                if (!normalizeLifecycleStatus(latest.lifecycleStatus()).equals(target)) {
                    throw new BusinessException(ErrorCode.CONFLICT, "评估报告生命周期已被其他操作改变，请重新读取");
                }
            }
        }
        completeAudit(tenantId, reportId, operationKey, recordedAction, current, target,
            normalizedOperator, secondApprover, reason, previous);
        return releaseRepository.findEvaluationReport(tenantId, reportId);
    }

    /** Scans one bounded tenant batch; due rows remain retryable if a transition fails. */
    @Transactional
    public int expireDueReports() {
        long tenantId = TenantContext.currentTenantIdOrDefault();
        LocalDateTime now = LocalDateTime.now();
        releaseRepository.lockTenant(tenantId);
        List<Long> ids = jdbcTemplate.query(
            """
                select id from llm_evaluation_report
                 where tenant_id = ? and lifecycle_status = 'ACTIVE'
                   and ((expires_at is not null and expires_at <= ?)
                     or (expires_at is null and date_add(created_at, interval retention_days day) <= ?))
                 order by coalesce(expires_at, created_at), id limit ?
                """,
            (rs, rowNum) -> rs.getLong(1), tenantId, now, now, EXPIRY_BATCH_SIZE
        );
        int changed = 0;
        for (Long reportId : ids) {
            if (reportId == null || reportId < 1) continue;
            LlmModelReleaseRepository.StoredEvaluationReport report = releaseRepository.findEvaluationReport(tenantId, reportId);
            if (!"ACTIVE".equalsIgnoreCase(report.lifecycleStatus())) continue;
            boolean referenced = releaseReferenceCount(tenantId, reportId) > 0;
            String target = referenced ? "FROZEN" : "EXPIRED";
            String action = referenced ? "FREEZE" : "EXPIRE";
            String expiryKey = report.effectiveExpiresAt() == null ? "unknown" : report.effectiveExpiresAt().toString();
            String operationKey = "retention-expiry:" + reportId + ":" + expiryKey;
            LifecycleAudit previous = findAudit(tenantId, operationKey);
            if (previous != null && "COMPLETED".equalsIgnoreCase(previous.status())) continue;
            int updated = updateLifecycle(tenantId, reportId, "ACTIVE", target, action, "保留期限到期自动治理");
            if (updated != 1) {
                LlmModelReleaseRepository.StoredEvaluationReport latest = releaseRepository.findEvaluationReport(tenantId, reportId);
                if (!target.equalsIgnoreCase(normalizeLifecycleStatus(latest.lifecycleStatus()))) {
                    throw new BusinessException(ErrorCode.CONFLICT, "评估报告到期状态已被其他操作改变，请稍后重试");
                }
            } else {
                changed++;
            }
            completeAudit(tenantId, reportId, operationKey, action, "ACTIVE", target,
                "retention-worker", null, "保留期限到期自动治理", previous);
        }
        return changed;
    }

    /** Authorizes and records an aggregate report export; only tenant/platform administrators may export. */
    @Transactional
    public LlmModelReleaseRepository.StoredEvaluationReport authorizeExport(
        long reportId, String format, String operator, String role
    ) {
        if (!isAdministrator(role)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "评估报告导出需要管理员授权");
        }
        long tenantId = TenantContext.currentTenantIdOrDefault();
        LlmModelReleaseRepository.StoredEvaluationReport report = releaseRepository.findEvaluationReport(tenantId, reportId);
        if ("DELETED".equalsIgnoreCase(report.lifecycleStatus())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "评估报告已软删除，不能导出");
        }
        String normalizedFormat = "html".equalsIgnoreCase(format) ? "html" : "json";
        String normalizedOperator = normalize(operator, "system", 128);
        String operationKey = "export:" + reportId + ":" + normalizedFormat + ":" + normalizedOperator;
        LifecycleAudit previous = findAudit(tenantId, operationKey);
        if (previous == null || !"COMPLETED".equalsIgnoreCase(previous.status())) {
            completeAudit(tenantId, reportId, operationKey, "EXPORT", report.lifecycleStatus(),
                report.lifecycleStatus(), normalizedOperator, null, "管理员导出聚合评估报告", previous);
        }
        return report;
    }

    private int updateLifecycle(long tenantId, long reportId, String expected, String target,
        String action, String reason) {
        return jdbcTemplate.update(
            """
                update llm_evaluation_report
                   set lifecycle_status = ?,
                       authorization_revoked_at = case when ? = 'REVOKE_AUTHORIZATION'
                           then coalesce(authorization_revoked_at, current_timestamp(6)) else authorization_revoked_at end,
                       authorization_revocation_reason = case when ? = 'REVOKE_AUTHORIZATION'
                           then ? else authorization_revocation_reason end,
                       frozen_at = case when ? = 'FROZEN' then coalesce(frozen_at, current_timestamp(6)) else frozen_at end,
                       deleted_at = case when ? = 'DELETED' then coalesce(deleted_at, current_timestamp(6)) else deleted_at end,
                       lifecycle_version = lifecycle_version + 1,
                       updated_at = current_timestamp(6)
                 where tenant_id = ? and id = ? and lifecycle_status = ?
                """,
            target, action, action, reason, target, target, tenantId, reportId, expected
        );
    }

    private long releaseReferenceCount(long tenantId, long reportId) {
        Long count = jdbcTemplate.queryForObject(
            "select count(*) from llm_model_release where tenant_id = ? and evaluation_report_id = ?",
            Long.class, tenantId, reportId
        );
        return count == null ? 0L : Math.max(0L, count);
    }

    private void completeAudit(long tenantId, long reportId, String operationKey, String action,
        String fromStatus, String toStatus, String operator, String secondApprover, String reason,
        LifecycleAudit previous) {
        if (previous != null) {
            jdbcTemplate.update(
                """
                    update llm_evaluation_report_lifecycle_audit
                       set action = ?, from_status = ?, to_status = ?, operator = ?, second_approver = ?,
                           reason = ?, status = 'COMPLETED', failure_code = null, updated_at = current_timestamp(6)
                     where tenant_id = ? and operation_key = ?
                    """,
                action, fromStatus, toStatus, operator, secondApprover, reason, tenantId, operationKey
            );
            return;
        }
        try {
            jdbcTemplate.update(
                """
                    insert into llm_evaluation_report_lifecycle_audit
                        (tenant_id, report_id, operation_key, action, from_status, to_status, operator,
                         second_approver, reason, status, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'COMPLETED', current_timestamp(6), current_timestamp(6))
                    """,
                tenantId, reportId, operationKey, action, fromStatus, toStatus, operator,
                secondApprover, reason
            );
        } catch (DuplicateKeyException duplicate) {
            // Another request completed the same idempotency key; its durable state wins.
        }
    }

    private LifecycleAudit findAudit(long tenantId, String operationKey) {
        List<LifecycleAudit> audits = jdbcTemplate.query(
            "select report_id, status from llm_evaluation_report_lifecycle_audit where tenant_id = ? and operation_key = ?",
            (rs, rowNum) -> new LifecycleAudit(rs.getLong("report_id"), rs.getString("status")),
            tenantId, operationKey
        );
        return audits.isEmpty() ? null : audits.getFirst();
    }

    private String targetStatus(String action, String current, boolean referenced) {
        return switch (action) {
            case "FREEZE" -> "FROZEN";
            case "REVOKE_AUTHORIZATION" -> "AUTHORIZATION_REVOKED";
            case "DELETE" -> referenced ? "FROZEN" : "DELETED";
            default -> throw new BusinessException(ErrorCode.BAD_REQUEST, "不支持的评估报告生命周期操作");
        };
    }

    private void ensureTransitionAllowed(String action, String current) {
        if ("DELETED".equals(current)) {
            throw new BusinessException(ErrorCode.CONFLICT, "评估报告已软删除，不能再次变更");
        }
        if ("FREEZE".equals(action) && "FROZEN".equals(current)) return;
        if ("REVOKE_AUTHORIZATION".equals(action) && "AUTHORIZATION_REVOKED".equals(current)) return;
        if ("DELETE".equals(action) && ("FROZEN".equals(current) || "DELETED".equals(current))) return;
        if (!List.of("ACTIVE", "EXPIRED", "AUTHORIZATION_REVOKED", "FROZEN").contains(current)) {
            throw new BusinessException(ErrorCode.CONFLICT, "评估报告当前状态不允许该生命周期操作");
        }
        if ("REVOKE_AUTHORIZATION".equals(action) && "FROZEN".equals(current)) {
            throw new BusinessException(ErrorCode.CONFLICT, "已冻结的评估报告不能撤销授权");
        }
    }

    private void requireApproval(String operator, String secondApprover, String role) {
        if (isAdministrator(role)) return;
        if (secondApprover == null || secondApprover.equalsIgnoreCase(operator)) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "该操作需要不同管理员的二次审批");
        }
    }

    private boolean isAdministrator(String role) {
        String normalized = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
        return "ADMIN".equals(normalized) || "PLATFORM_ADMIN".equals(normalized);
    }

    private String normalizeAction(String value) {
        String normalized = normalize(value, null, 32);
        if (normalized == null) throw new BusinessException(ErrorCode.BAD_REQUEST, "生命周期操作不能为空");
        return normalized.toUpperCase(Locale.ROOT);
    }

    private String normalizeLifecycleStatus(String value) {
        return value == null || value.isBlank() ? "ACTIVE" : value.trim().toUpperCase(Locale.ROOT);
    }

    private String normalize(String value, String fallback, int maxLength) {
        if (value == null || value.isBlank()) return fallback;
        String trimmed = value.trim();
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private static int normalizeRetentionDays(int value) {
        return value < MIN_RETENTION_DAYS
            ? DEFAULT_RETENTION_DAYS
            : Math.min(MAX_RETENTION_DAYS, value);
    }

    private record LifecycleAudit(Long reportId, String status) {
    }
}
