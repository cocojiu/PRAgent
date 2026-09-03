package com.repoguard.agent.scanner;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.CiSarifUploadResponse;
import com.repoguard.agent.dto.SarifImportRequest;
import com.repoguard.agent.dto.SarifImportResponse;
import com.repoguard.agent.entity.ReviewExecutionAttempt;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewExecutionAttemptMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper.SarifImportBatchRow;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.SarifCiUploadMapper;
import com.repoguard.agent.mapper.SarifCiUploadMapper.SarifCiUploadRow;
import com.repoguard.agent.tenancy.TenantContext;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.regex.Pattern;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/** Validates CI identity and atomically imports one scanner run into the current attempt. */
@Service
public class CiSarifUploadService {

    private static final Pattern SCAN_RUN_PATTERN = Pattern.compile("[A-Za-z0-9._:-]{1,128}");
    private static final int MAX_TOOL_NAME = 128;
    private static final int MAX_TOOL_VERSION = 64;

    private final CiSarifUploadCredentialService credentialService;
    private final CiSarifPayloadDecoder payloadDecoder;
    private final SarifFindingService sarifFindingService;
    private final SarifCiUploadMapper uploadMapper;
    private final ReviewTaskMapper taskMapper;
    private final ReviewExecutionAttemptMapper attemptMapper;
    private final ReviewFindingMapper findingMapper;

    public CiSarifUploadService(
        CiSarifUploadCredentialService credentialService,
        CiSarifPayloadDecoder payloadDecoder,
        SarifFindingService sarifFindingService,
        SarifCiUploadMapper uploadMapper,
        ReviewTaskMapper taskMapper,
        ReviewExecutionAttemptMapper attemptMapper,
        ReviewFindingMapper findingMapper
    ) {
        this.credentialService = Objects.requireNonNull(credentialService, "credentialService");
        this.payloadDecoder = Objects.requireNonNull(payloadDecoder, "payloadDecoder");
        this.sarifFindingService = Objects.requireNonNull(sarifFindingService, "sarifFindingService");
        this.uploadMapper = Objects.requireNonNull(uploadMapper, "uploadMapper");
        this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper");
        this.attemptMapper = Objects.requireNonNull(attemptMapper, "attemptMapper");
        this.findingMapper = Objects.requireNonNull(findingMapper, "findingMapper");
    }

    @Transactional
    public CiSarifUploadResponse upload(
        Long taskId,
        String credential,
        String toolName,
        String toolVersion,
        String scanRunId,
        String commitSha,
        String completedAt,
        String contentType,
        byte[] payload
    ) {
        CiSarifUploadCredentialService.Claims claims = credentialService.verify(credential);
        if (taskId == null || taskId < 1 || claims.taskId() != taskId) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "CI credential is not bound to this task");
        }
        String normalizedTool = requiredHeader(toolName, MAX_TOOL_NAME, "X-RepoGuard-CI-Tool");
        String normalizedVersion = optionalHeader(toolVersion, MAX_TOOL_VERSION, "X-RepoGuard-CI-Tool-Version");
        String normalizedRun = requiredScanRun(scanRunId);
        String normalizedCommit = requiredHeader(commitSha, 64, "X-RepoGuard-CI-Commit-SHA");
        if (normalizedCommit.contains(" ") || normalizedCommit.contains("\t")
            || !normalizedCommit.equals(claims.commitSha())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "CI commit SHA does not match the credential");
        }
        OffsetDateTime completion = parseCompletion(completedAt);
        String content = payloadDecoder.decode(payload, contentType);
        String fingerprint = sarifFindingService.contentFingerprint(content);

        try (TenantContext.Scope _ = TenantContext.withTenant(claims.tenantId())) {
            ReviewTask task = requireTask(taskId);
            ReviewExecutionAttempt attempt = requireAttempt(taskId, claims.attemptId(), task);
            String taskCommit = text(attempt.getCommitSha(), task.getCommitSha());
            if (!Objects.equals(taskCommit, claims.commitSha()) || !Objects.equals(taskCommit, normalizedCommit)
                || !sameIgnoreCase(task.getOrganization(), claims.organization())
                || !sameIgnoreCase(task.getRepository(), claims.repository())
                || !Objects.equals(task.getPrNumber(), claims.prNumber())) {
                throw new BusinessException(ErrorCode.CONFLICT, "CI credential no longer matches the review attempt");
            }
            SarifCiUploadRow existing = uploadMapper.selectByIdentity(
                taskId, claims.attemptId(), normalizedTool, normalizedVersion, normalizedCommit, normalizedRun
            );
            if (existing != null && fingerprint.equals(existing.getSarifFingerprint())) {
                return response(existing);
            }

            SarifImportResponse imported = sarifFindingService.importFindings(
                taskId,
                new SarifImportRequest(content)
            );
            SarifImportBatchRow batch = findingMapper.selectSarifImportBatchByFingerprint(
                taskId, claims.attemptId(), normalizedTool, normalizedVersion, normalizedCommit, fingerprint
            );
            if (batch == null || batch.getId() == null) {
                throw new IllegalStateException("SARIF import batch was not created");
            }
            if (!normalizedTool.equals(batch.getToolName()) || !normalizedVersion.equals(batch.getToolVersion())) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "CI tool metadata does not match the SARIF document");
            }
            LocalDateTime now = LocalDateTime.now(ZoneOffset.UTC);
            SarifCiUploadRow row = existing == null ? new SarifCiUploadRow() : existing;
            row.setTenantId(claims.tenantId());
            row.setTaskId(taskId);
            row.setAttemptId(claims.attemptId());
            row.setBatchId(batch.getId());
            row.setToolName(normalizedTool);
            row.setToolVersion(normalizedVersion);
            row.setScanRunId(normalizedRun);
            row.setCommitSha(normalizedCommit);
            row.setSarifFingerprint(fingerprint);
            row.setCompletionTime(completion.toLocalDateTime());
            row.setStatus("ACTIVE");
            row.setImportedCount(imported.imported());
            row.setSkippedCount(imported.skipped());
            row.setUpdatedAt(now);
            if (existing == null) {
                row.setCreatedAt(now);
                try {
                    uploadMapper.insert(row);
                } catch (DuplicateKeyException race) {
                    SarifCiUploadRow raced = uploadMapper.selectByIdentity(
                        taskId, claims.attemptId(), normalizedTool, normalizedVersion, normalizedCommit, normalizedRun
                    );
                    if (raced != null && fingerprint.equals(raced.getSarifFingerprint())) {
                        return response(raced);
                    }
                    throw race;
                }
            } else {
                uploadMapper.replace(row);
            }
            return new CiSarifUploadResponse(
                taskId,
                claims.attemptId(),
                normalizedTool,
                normalizedVersion,
                normalizedRun,
                normalizedCommit,
                fingerprint,
                completion,
                "ACTIVE",
                imported.imported(),
                imported.skipped()
            );
        }
    }

    private ReviewTask requireTask(Long taskId) {
        ReviewTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + taskId);
        }
        return task;
    }

    private ReviewExecutionAttempt requireAttempt(Long taskId, long attemptId, ReviewTask task) {
        if (!Objects.equals(task.getCurrentAttemptId(), attemptId)) {
            throw new BusinessException(ErrorCode.CONFLICT, "CI upload must target the current review attempt");
        }
        ReviewExecutionAttempt attempt = attemptMapper.selectById(attemptId);
        if (attempt == null || !Objects.equals(taskId, attempt.getTaskId())) {
            throw new BusinessException(ErrorCode.CONFLICT, "The review attempt is missing or mismatched");
        }
        return attempt;
    }

    private String requiredHeader(String value, int maxLength, String name) {
        String normalized = value == null ? "" : value.trim();
        if (!StringUtils.hasText(normalized) || normalized.length() > maxLength
            || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, name + " is invalid");
        }
        return normalized;
    }

    private String optionalHeader(String value, int maxLength, String name) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        return requiredHeader(value, maxLength, name);
    }

    private String requiredScanRun(String value) {
        String normalized = requiredHeader(value, 128, "X-RepoGuard-CI-Scan-Run");
        if (!SCAN_RUN_PATTERN.matcher(normalized).matches()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "X-RepoGuard-CI-Scan-Run is invalid");
        }
        return normalized;
    }

    private OffsetDateTime parseCompletion(String value) {
        if (!StringUtils.hasText(value)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "X-RepoGuard-CI-Completed-At is required");
        }
        try {
            OffsetDateTime parsed = OffsetDateTime.parse(value.trim());
            if (parsed.isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5))) {
                throw new BusinessException(ErrorCode.BAD_REQUEST, "CI completion time cannot be in the future");
            }
            return parsed;
        } catch (java.time.format.DateTimeParseException ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "X-RepoGuard-CI-Completed-At must be RFC3339");
        }
    }

    private boolean sameIgnoreCase(String left, String right) {
        return text(left, "").equalsIgnoreCase(text(right, ""));
    }

    private String text(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private CiSarifUploadResponse response(SarifCiUploadRow row) {
        return new CiSarifUploadResponse(
            row.getTaskId(),
            row.getAttemptId(),
            row.getToolName(),
            row.getToolVersion(),
            row.getScanRunId(),
            row.getCommitSha(),
            row.getSarifFingerprint(),
            row.getCompletionTime() == null ? null : row.getCompletionTime().atOffset(ZoneOffset.UTC),
            row.getStatus(),
            row.getImportedCount() == null ? 0 : row.getImportedCount(),
            row.getSkippedCount() == null ? 0 : row.getSkippedCount()
        );
    }
}
