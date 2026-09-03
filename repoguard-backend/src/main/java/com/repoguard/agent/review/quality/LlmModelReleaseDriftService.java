package com.repoguard.agent.review.quality;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.LlmModelReleaseDto;
import com.repoguard.agent.dto.LlmModelReleaseDriftDto;
import com.repoguard.agent.dto.LlmModelReleaseDriftDto.AssignmentDto;
import com.repoguard.agent.dto.LlmModelReleaseDriftDto.AssignmentSummary;
import com.repoguard.agent.dto.LlmModelReleaseDriftDto.FindingDto;
import com.repoguard.agent.dto.LlmModelReleaseDriftDto.ReleaseSummary;
import com.repoguard.agent.dto.LlmModelReleaseDriftRepairDto;
import com.repoguard.agent.dto.LlmModelReleaseDriftRepairRequest;
import com.repoguard.agent.tenancy.TenantContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.interceptor.TransactionAspectSupport;

/** Detects release/assignment drift and applies only explicit, queued-task-safe repairs. */
@Service
public class LlmModelReleaseDriftService {

    private static final int MAX_ASSIGNMENTS = 500;
    private static final Set<String> REPAIR_ROLES = Set.of("ADMIN", "PLATFORM_ADMIN", "RULE_ADMIN");
    private static final Logger LOGGER = LoggerFactory.getLogger(LlmModelReleaseDriftService.class);

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;
    private final LlmModelReleaseRepository releaseRepository;
    private final LlmModelReleaseDriftAuditRepository auditRepository;
    private final LlmModelReleaseDriftFailureRecorder failureRecorder;

    public LlmModelReleaseDriftService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
        LlmModelReleaseRepository releaseRepository, LlmModelReleaseDriftAuditRepository auditRepository) {
        this(jdbcTemplate, objectMapper, releaseRepository, auditRepository,
            new LlmModelReleaseDriftFailureRecorder(auditRepository));
    }

    @Autowired
    public LlmModelReleaseDriftService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper,
        LlmModelReleaseRepository releaseRepository, LlmModelReleaseDriftAuditRepository auditRepository,
        LlmModelReleaseDriftFailureRecorder failureRecorder) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
        this.releaseRepository = releaseRepository;
        this.auditRepository = auditRepository;
        this.failureRecorder = failureRecorder;
    }

    /** Read-only tenant reconciliation; the returned fingerprint is the repair CAS token. */
    @Transactional(readOnly = true)
    public LlmModelReleaseDriftDto detect() {
        return detectTenant(TenantContext.currentTenantIdOrDefault());
    }

    /** Applies one exact preview once. Replays return the durable outcome without new writes. */
    @Transactional
    public LlmModelReleaseDriftRepairDto repair(LlmModelReleaseDriftRepairRequest request,
        String operator, String role) {
        validateRequest(request, role);
        long tenantId = TenantContext.currentTenantIdOrDefault();
        String operationKey = request.idempotencyKey().trim();
        String fingerprint = request.previewFingerprint().trim().toLowerCase(Locale.ROOT);
        LlmModelReleaseDriftAuditRepository.StoredAudit existing = auditRepository.find(tenantId, operationKey);
        if (existing != null) {
            ensureSamePreview(existing, fingerprint);
            if ("COMPLETED".equals(existing.status()) || "FAILED".equals(existing.status())) {
                return toRepairDto(existing);
            }
        }

        LlmModelReleaseDriftDto preview = detectTenant(tenantId);
        if (!fingerprint.equals(preview.fingerprint())) {
            throw new BusinessException(ErrorCode.CONFLICT, "发布状态已变化，请重新生成漂移预览");
        }
        if (existing == null) {
            try {
                existing = auditRepository.insertPreview(tenantId, operationKey, fingerprint,
                    normalizeOperator(operator), preview);
            } catch (DuplicateKeyException duplicate) {
                existing = auditRepository.find(tenantId, operationKey);
                if (existing == null) throw duplicate;
                ensureSamePreview(existing, fingerprint);
                if ("COMPLETED".equals(existing.status()) || "FAILED".equals(existing.status())) {
                    return toRepairDto(existing);
                }
            }
        }
        auditRepository.markRunning(tenantId, operationKey);
        try {
            releaseRepository.lockTenant(tenantId);
            LlmModelReleaseDriftDto lockedPreview = detectTenant(tenantId);
            if (!fingerprint.equals(lockedPreview.fingerprint())) {
                throw new BusinessException(ErrorCode.CONFLICT, "加锁后发布状态已变化，请重新生成漂移预览");
            }
            int changedReleases = repairReleaseStates(tenantId, lockedPreview);
            int changedTasks = repairQueuedAssignments(tenantId, lockedPreview);
            LlmModelReleaseDriftDto after = detectTenant(tenantId);
            LlmModelReleaseDriftAuditRepository.StoredAudit completed = auditRepository.complete(
                tenantId, operationKey, after, changedReleases, changedTasks,
                lockedPreview.assignmentSummary().runningTaskDriftCount()
            );
            return toRepairDto(completed);
        } catch (BusinessException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            LOGGER.warn("发布状态漂移修复失败 operationKey={}，保留原状态并进入告警", operationKey, ex);
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
            }
            LlmModelReleaseDriftAuditRepository.StoredAudit failed = failureRecorder.record(
                tenantId, operationKey, failureCode(ex)
            );
            return toRepairDto(failed);
        }
    }

    private LlmModelReleaseDriftDto detectTenant(long tenantId) {
        List<LlmModelReleaseDto> releases = releaseRepository.findAll(tenantId);
        List<LlmModelReleaseDto> active = state(releases, "ACTIVE");
        List<LlmModelReleaseDto> canary = state(releases, "CANARY");
        List<FindingDto> findings = new ArrayList<>();
        addDuplicateFinding(findings, active, "MULTIPLE_ACTIVE_RELEASES", "ACTIVE", "ACTIVE");
        addDuplicateFinding(findings, canary, "MULTIPLE_CANARY_RELEASES", "CANARY", "CANARY");
        for (LlmModelReleaseDto release : releases) {
            int desiredTraffic = desiredTraffic(release.state(), release.trafficPercent());
            if (desiredTraffic != release.trafficPercent()) {
                findings.add(new FindingDto("RELEASE_TRAFFIC_STATE_MISMATCH", "MEDIUM", "RELEASE",
                    release.id(), release.releaseKey(), release.state() + ":" + release.trafficPercent(),
                    release.state() + ":" + desiredTraffic, true));
            }
        }

        Map<String, LlmModelReleaseDto> releasesByKey = new HashMap<>();
        releases.forEach(release -> releasesByKey.put(release.releaseKey(), release));
        List<Map<String, Object>> assignments = assignmentRows(tenantId);
        int missing = 0;
        int metadataMismatch = 0;
        int runningDrift = 0;
        List<AssignmentDto> assignmentSamples = new ArrayList<>();
        for (Map<String, Object> row : assignments) {
            String key = text(row.get("llm_release_key"));
            LlmModelReleaseDto release = releasesByKey.get(key);
            String issue = null;
            String desired = "";
            if (release == null) {
                missing++;
                issue = "ASSIGNMENT_RELEASE_MISSING";
                desired = desiredReleaseKey(active, canary);
            } else if (!sameText(row.get("llm_provider"), release.provider())
                || !sameText(row.get("llm_model"), release.modelName())) {
                metadataMismatch++;
                issue = "ASSIGNMENT_METADATA_MISMATCH";
                desired = release.provider() + "/" + release.modelName();
            }
            if (issue == null) continue;
            boolean started = started(row);
            if (started) runningDrift++;
            assignmentSamples.add(new AssignmentDto(numberLong(row.get("id")), key, text(row.get("llm_provider")),
                text(row.get("llm_model")), text(row.get("status")), started, issue, !started));
            findings.add(new FindingDto(issue, started ? "HIGH" : "MEDIUM", "REVIEW_TASK",
                numberLong(row.get("id")), key, text(row.get("llm_provider")) + "/" + text(row.get("llm_model")),
                desired, !started));
        }
        ReleaseSummary releaseSummary = new ReleaseSummary(active.size(), canary.size(),
            active.stream().map(LlmModelReleaseDto::id).toList(), canary.stream().map(LlmModelReleaseDto::id).toList(),
            firstId(active), firstId(canary));
        AssignmentSummary assignmentSummary = new AssignmentSummary(assignments.size(), missing, metadataMismatch,
            runningDrift, List.copyOf(assignmentSamples));
        String fingerprint = fingerprint(findings, releaseSummary, assignmentSummary);
        return new LlmModelReleaseDriftDto(LocalDateTime.now(), findings.isEmpty(), fingerprint,
            List.copyOf(findings), releaseSummary, assignmentSummary);
    }

    private int repairReleaseStates(long tenantId, LlmModelReleaseDriftDto preview) {
        int changed = 0;
        Long desiredActive = preview.releaseSummary().desiredActiveReleaseId();
        Long desiredCanary = preview.releaseSummary().desiredCanaryReleaseId();
        for (Long id : preview.releaseSummary().activeReleaseIds()) {
            if (!id.equals(desiredActive)) {
                changed += jdbcTemplate.update("""
                    update llm_model_release set state = 'ROLLED_BACK', traffic_percent = 0,
                        rollback_reason = '状态漂移人工修复', updated_at = current_timestamp(6)
                     where tenant_id = ? and id = ? and state = 'ACTIVE'
                    """, tenantId, id);
            }
        }
        for (Long id : preview.releaseSummary().canaryReleaseIds()) {
            if (!id.equals(desiredCanary)) {
                changed += jdbcTemplate.update("""
                    update llm_model_release set state = 'ROLLED_BACK', traffic_percent = 0,
                        rollback_reason = '状态漂移人工修复', updated_at = current_timestamp(6)
                     where tenant_id = ? and id = ? and state = 'CANARY'
                    """, tenantId, id);
            }
        }
        for (FindingDto finding : preview.findings()) {
            if (!"RELEASE_TRAFFIC_STATE_MISMATCH".equals(finding.code()) || finding.resourceId() == null) continue;
            int traffic = parseDesiredTraffic(finding.desiredValue());
            changed += jdbcTemplate.update("""
                update llm_model_release set traffic_percent = ?, updated_at = current_timestamp(6)
                 where tenant_id = ? and id = ? and state = ?
                """, traffic, tenantId, finding.resourceId(), stateFrom(finding.desiredValue()));
        }
        return changed;
    }

    private int repairQueuedAssignments(long tenantId, LlmModelReleaseDriftDto preview) {
        Map<String, LlmModelReleaseDto> releases = new HashMap<>();
        for (AssignmentDto assignment : preview.assignmentSummary().samples()) {
            // The release key is resolved by a tenant-scoped query below; this map is filled lazily.
            if (assignment.releaseKey() != null) {
                LlmModelReleaseDto release = releaseRepository.findByReleaseKey(tenantId, assignment.releaseKey());
                if (release != null) releases.put(assignment.releaseKey(), release);
            }
        }
        LlmModelReleaseDto desired = desiredRelease(tenantId, preview);
        int changed = 0;
        for (AssignmentDto assignment : preview.assignmentSummary().samples()) {
            if (!Boolean.TRUE.equals(assignment.repairable())) continue;
            LlmModelReleaseDto release = releases.get(assignment.releaseKey());
            if ("ASSIGNMENT_RELEASE_MISSING".equals(assignment.issueCode())) {
                if (desired == null) {
                    changed += jdbcTemplate.update("""
                        update review_task set llm_release_key = null, llm_provider = null, llm_model = null
                         where tenant_id = ? and id = ? and status = 'QUEUED'
                           and started_at is null and review_claimed_at is null
                        """, tenantId, assignment.taskId());
                } else {
                    changed += jdbcTemplate.update("""
                        update review_task set llm_release_key = ?, llm_provider = ?, llm_model = ?
                         where tenant_id = ? and id = ? and status = 'QUEUED'
                           and started_at is null and review_claimed_at is null
                        """, desired.releaseKey(), desired.provider(), desired.modelName(), tenantId, assignment.taskId());
                }
            } else if (release != null) {
                changed += jdbcTemplate.update("""
                    update review_task set llm_provider = ?, llm_model = ?
                     where tenant_id = ? and id = ? and status = 'QUEUED'
                       and started_at is null and review_claimed_at is null
                    """, release.provider(), release.modelName(), tenantId, assignment.taskId());
            }
        }
        return changed;
    }

    private LlmModelReleaseDto desiredRelease(long tenantId, LlmModelReleaseDriftDto preview) {
        Long id = preview.releaseSummary().desiredActiveReleaseId();
        if (id == null) id = preview.releaseSummary().desiredCanaryReleaseId();
        return id == null ? null : releaseRepository.findById(tenantId, id);
    }

    private List<Map<String, Object>> assignmentRows(long tenantId) {
        return jdbcTemplate.queryForList("""
            select id, llm_release_key, llm_provider, llm_model, status, started_at, review_claimed_at
              from review_task
             where tenant_id = ? and llm_release_key is not null and trim(llm_release_key) <> ''
             order by id desc
             limit ?
            """, tenantId, MAX_ASSIGNMENTS);
    }

    private void addDuplicateFinding(List<FindingDto> findings, List<LlmModelReleaseDto> releases,
        String code, String observedState, String desiredState) {
        if (releases.size() <= 1) return;
        findings.add(new FindingDto(code, "HIGH", "RELEASE", null, observedState,
            Integer.toString(releases.size()), desiredState + ":" + releases.getFirst().id(), true));
    }

    private List<LlmModelReleaseDto> state(List<LlmModelReleaseDto> releases, String expected) {
        return releases.stream().filter(release -> expected.equalsIgnoreCase(release.state()))
            .sorted(Comparator.comparing(LlmModelReleaseDto::updatedAt,
                Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(LlmModelReleaseDto::id, Comparator.reverseOrder()))
            .toList();
    }

    private int desiredTraffic(String state, Integer current) {
        if ("ACTIVE".equalsIgnoreCase(state)) return 100;
        if ("CANARY".equalsIgnoreCase(state)) return current == null || current < 1 ? 1 : Math.min(99, current);
        return 0;
    }

    private String desiredReleaseKey(List<LlmModelReleaseDto> active, List<LlmModelReleaseDto> canary) {
        if (!active.isEmpty()) return active.getFirst().releaseKey();
        return canary.isEmpty() ? "" : canary.getFirst().releaseKey();
    }

    private Long firstId(List<LlmModelReleaseDto> releases) {
        return releases.isEmpty() ? null : releases.getFirst().id();
    }

    private boolean started(Map<String, Object> row) {
        String status = text(row.get("status")).toUpperCase(Locale.ROOT);
        return !"QUEUED".equals(status) || row.get("started_at") != null || row.get("review_claimed_at") != null;
    }

    private boolean sameText(Object value, String expected) {
        return expected != null && expected.equalsIgnoreCase(text(value));
    }

    private void validateRequest(LlmModelReleaseDriftRepairRequest request, String role) {
        if (request == null || !Boolean.TRUE.equals(request.confirm())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "漂移修复必须显式确认");
        }
        if (!REPAIR_ROLES.contains(role == null ? "" : role.trim().toUpperCase(Locale.ROOT))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "只有发布管理员可以执行漂移修复");
        }
    }

    private void ensureSamePreview(LlmModelReleaseDriftAuditRepository.StoredAudit existing, String fingerprint) {
        if (!fingerprint.equalsIgnoreCase(existing.previewFingerprint())) {
            throw new BusinessException(ErrorCode.CONFLICT, "幂等键已经绑定其他漂移预览");
        }
    }

    private LlmModelReleaseDriftRepairDto toRepairDto(LlmModelReleaseDriftAuditRepository.StoredAudit stored) {
        if (stored == null) return null;
        return new LlmModelReleaseDriftRepairDto(stored.operationKey(), stored.previewFingerprint(), stored.status(),
            stored.changedReleaseCount(), stored.changedTaskCount(), stored.skippedRunningTaskCount(), stored.failureCode(),
            stored.createdAt(), stored.updatedAt(), read(stored.beforeJson()), read(stored.afterJson()));
    }

    private LlmModelReleaseDriftDto read(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, LlmModelReleaseDriftDto.class);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("发布漂移审计详情读取失败", ex);
        }
    }

    private String fingerprint(List<FindingDto> findings, ReleaseSummary releases, AssignmentSummary assignments) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("findings", findings);
        payload.put("releaseSummary", releases);
        payload.put("assignmentSummary", assignments);
        try {
            return sha256(objectMapper.writeValueAsString(payload));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("发布漂移指纹生成失败", ex);
        }
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte item : digest) result.append(String.format("%02x", item));
            return result.toString();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is required for drift fingerprints", ex);
        }
    }

    private int parseDesiredTraffic(String desired) {
        if (desired == null || !desired.contains(":")) return 0;
        try { return Integer.parseInt(desired.substring(desired.indexOf(':') + 1)); }
        catch (NumberFormatException ex) { return 0; }
    }

    private String stateFrom(String desired) {
        if (desired == null || !desired.contains(":")) return "SHADOW";
        return desired.substring(0, desired.indexOf(':'));
    }

    private Long numberLong(Object value) {
        if (value instanceof Number number) return number.longValue();
        try { return Long.parseLong(text(value)); } catch (NumberFormatException ex) { return 0L; }
    }

    private String text(Object value) { return value == null ? "" : value.toString(); }

    private String normalizeOperator(String value) {
        if (value == null || value.isBlank()) return "system";
        return value.trim().length() <= 128 ? value.trim() : value.trim().substring(0, 128);
    }

    private String failureCode(RuntimeException exception) {
        String name = exception.getClass().getSimpleName().toUpperCase(Locale.ROOT);
        return name.length() <= 64 ? name : name.substring(0, 64);
    }
}
