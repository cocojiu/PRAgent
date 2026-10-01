package com.repoguard.agent.scanner;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.SarifExportDto;
import com.repoguard.agent.dto.SarifImportRequest;
import com.repoguard.agent.dto.SarifImportResponse;
import com.repoguard.agent.dto.SarifImportedFindingDto;
import com.repoguard.agent.entity.ReviewExecutionAttempt;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewExecutionAttemptMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.util.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import com.repoguard.agent.mapper.ReviewFindingMapper.SarifImportBatchRow;
import com.repoguard.agent.tenancy.TenantContext;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
@Service
public class SarifFindingService {
    @Value("${repoguard.sarif-export.max-findings:10000}")
    private int exportMaxFindings = 10_000;
    @Value("${repoguard.sarif-export.max-document-bytes:8388608}")
    private int exportMaxDocumentBytes = 8 * 1024 * 1024;
    private final ObjectMapper objectMapper;
    private final SarifReportParser parser;
    private final SarifImportTransactions transactions;
    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewFindingMapper reviewFindingMapper;
    private final ReviewExecutionAttemptMapper reviewExecutionAttemptMapper;
    public SarifFindingService(
        ObjectMapper objectMapper,
        ReviewTaskMapper reviewTaskMapper,
        ReviewFindingMapper reviewFindingMapper,
        ReviewExecutionAttemptMapper reviewExecutionAttemptMapper,
        SarifImportTransactions transactions
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.parser = new SarifReportParser(objectMapper);
        this.transactions = Objects.requireNonNull(transactions, "transactions");
        this.reviewTaskMapper = Objects.requireNonNull(reviewTaskMapper, "reviewTaskMapper");
        this.reviewFindingMapper = Objects.requireNonNull(reviewFindingMapper, "reviewFindingMapper");
        this.reviewExecutionAttemptMapper = Objects.requireNonNull(
            reviewExecutionAttemptMapper,
            "reviewExecutionAttemptMapper"
        );
    }
    @Transactional(propagation = Propagation.NEVER)
    public SarifImportResponse importFindings(Long taskId, SarifImportRequest request) {
        ReviewTask task = requireTask(taskId);
        if (request == null || !StringUtils.hasText(request.content())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "SARIF content is required");
        }
        ReviewExecutionAttempt attempt = requireCurrentAttempt(taskId, task);
        ImportBinding observed = observe(task, attempt, resolveCommitSha(task, attempt));
        SarifReportParser.ParsedReport report = prepare(request);
        return transactions.execute(() -> importPrepared(taskId, observed, report));
    }

    SarifReportParser.ParsedReport prepare(SarifImportRequest request) { return parser.prepare(request); }

    @Transactional(propagation = Propagation.MANDATORY)
    public SarifImportResponse importPrepared(Long taskId, ImportBinding observed, SarifReportParser.ParsedReport report) {
        ReviewTask task = reviewTaskMapper.selectSarifImportTaskForUpdate(taskId);
        if (task == null) throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + taskId);
        ReviewExecutionAttempt attempt = requireCurrentAttempt(taskId, task);
        String commitSha = resolveCommitSha(task, attempt);
        if (!observed.equals(observe(task, attempt, commitSha))) {
            throw new BusinessException(ErrorCode.CONFLICT, "Review attempt changed while preparing the SARIF import");
        }
        SarifImportBatchRow existing = findBatch(taskId, attempt.getId(), report.toolName(), report.toolVersion(), commitSha, report.fingerprint());
        if (existing != null) return responseFromBatch(taskId, existing);
        SarifImportBatchRow batch = new SarifImportBatchRow();
        batch.setTenantId(TenantContext.currentTenantIdOrDefault());
        batch.setTaskId(taskId);
        batch.setAttemptId(attempt.getId());
        batch.setToolName(report.toolName());
        batch.setToolVersion(report.toolVersion());
        batch.setCommitSha(commitSha);
        batch.setContentFingerprint(report.fingerprint());
        batch.setStatus("ACTIVE");
        batch.setImportedCount(report.findings().size());
        batch.setSkippedCount(report.skipped());
        batch.setCreatedAt(java.time.LocalDateTime.now());
        batch.setUpdatedAt(batch.getCreatedAt());
        try {
            reviewFindingMapper.insertSarifImportBatch(batch);
        } catch (DuplicateKeyException ex) {
            SarifImportBatchRow raced = findBatch(
                taskId, attempt.getId(), report.toolName(), report.toolVersion(), commitSha, report.fingerprint());
            if (raced != null) {
                return responseFromBatch(taskId, raced);
            }
            throw ex;
        }
        if (batch.getId() == null) {
            throw new IllegalStateException("SARIF import batch id was not generated");
        }
        supersedePreviousBatches(taskId, attempt.getId(), report.toolName(), report.toolVersion(), commitSha, report.fingerprint());
        SarifFindingBatchWriter.insert(reviewFindingMapper, taskId, attempt.getId(), batch.getId(), report.results());
        return new SarifImportResponse(taskId, report.findings().size(), report.skipped(), report.findings());
    }
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public SarifExportDto exportFindings(Long taskId) {
        requireTask(taskId);
        return SarifExportBuilder.export(taskId, reviewFindingMapper, objectMapper, exportMaxFindings, exportMaxDocumentBytes);
    }

    @PostConstruct
    void validateExportLimits() {
        SarifExportBuilder.validateLimits(exportMaxFindings, exportMaxDocumentBytes);
    }

    /** Returns the canonical fingerprint used by the durable SARIF batch identity. */
    public String contentFingerprint(String content) {
        return parser.fingerprint(content == null ? "" : content);
    }

    private String text(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private ReviewTask requireTask(Long taskId) {
        ReviewTask task = taskId == null || taskId < 1 ? null : reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + taskId);
        }
        return task;
    }
    private ReviewExecutionAttempt requireCurrentAttempt(Long taskId, ReviewTask task) {
        Long attemptId = task.getCurrentAttemptId();
        if (attemptId == null || attemptId < 1) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "SARIF import requires a current review execution attempt"
            );
        }
        ReviewExecutionAttempt attempt = reviewExecutionAttemptMapper.selectById(attemptId);
        if (attempt == null || !Objects.equals(taskId, attempt.getTaskId())) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "The task current review execution attempt is missing or mismatched"
            );
        }
        return attempt;
    }
    private String resolveCommitSha(ReviewTask task, ReviewExecutionAttempt attempt) {
        String commitSha = text(attempt.getCommitSha(), task.getCommitSha());
        if (!StringUtils.hasText(commitSha)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "SARIF import requires the attempt commit SHA");
        }
        if (commitSha.length() > 64 || commitSha.contains(" ") || commitSha.contains("\t")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "The attempt commit SHA is invalid");
        }
        return commitSha;
    }
    private SarifImportBatchRow findBatch(Long taskId, Long attemptId, String tool, String version, String commit, String fingerprint) {
        return reviewFindingMapper.selectSarifImportBatch(taskId, attemptId, tool, version, commit, fingerprint);
    }
    private SarifImportResponse responseFromBatch(Long taskId, SarifImportBatchRow batch) {
        List<ReviewFinding> findings = batch.getId() == null ? List.of() : reviewFindingMapper.selectList(
            new LambdaQueryWrapper<ReviewFinding>().eq(ReviewFinding::getSourceBatchId, batch.getId()).orderByAsc(ReviewFinding::getId));
        List<SarifImportedFindingDto> imported = (findings == null ? List.<ReviewFinding>of() : findings).stream()
            .map(SarifFindingService::toImportedDto).toList();
        return new SarifImportResponse(taskId, batch.getImportedCount() == null ? imported.size() : batch.getImportedCount(),
            batch.getSkippedCount() == null ? 0 : batch.getSkippedCount(), imported);
    }
    private void supersedePreviousBatches(Long taskId, Long attemptId, String toolName, String toolVersion, String commit, String fingerprint) {
        List<SarifImportBatchRow> previous = reviewFindingMapper.selectActiveSarifImportBatches(
            taskId, attemptId, toolName, toolVersion, commit, fingerprint);
        if (previous != null) for (SarifImportBatchRow old : previous) {
            if (old.getId() == null) continue;
            reviewFindingMapper.update(null, new LambdaUpdateWrapper<ReviewFinding>()
                .eq(ReviewFinding::getTaskId, taskId).eq(ReviewFinding::getAttemptId, attemptId)
                .eq(ReviewFinding::getSourceBatchId, old.getId()).eq(ReviewFinding::getCurrentAttempt, true)
                .set(ReviewFinding::getCurrentAttempt, false));
            reviewFindingMapper.markSarifImportBatchSuperseded(old.getId(), java.time.LocalDateTime.now());
        }
        reviewFindingMapper.update(null, new LambdaUpdateWrapper<ReviewFinding>()
            .eq(ReviewFinding::getTaskId, taskId).eq(ReviewFinding::getAttemptId, attemptId)
            .eq(ReviewFinding::getSource, "SARIF").isNull(ReviewFinding::getSourceBatchId)
            .eq(ReviewFinding::getCurrentAttempt, true).set(ReviewFinding::getCurrentAttempt, false));
    }
    private static SarifImportedFindingDto toImportedDto(ReviewFinding finding) {
        return new SarifImportedFindingDto(
            finding.getRuleId(),
            finding.getFilePath(),
            finding.getLineNumber(),
            finding.getSeverity(),
            finding.getMessage()
        );
    }
    static ImportBinding observe(ReviewTask task, ReviewExecutionAttempt attempt, String commitSha) {
        return new ImportBinding(TenantContext.currentTenantIdOrDefault(), task.getId(), attempt.getId(), commitSha,
            task.getCommitSha(), task.getStatus(), task.getGeneration(), task.getMqRetries());
    }
    public record ImportBinding(long tenantId, Long taskId, Long attemptId, String commitSha,
                                String taskCommitSha, String taskStatus, Long generation, Integer retries) { }
}
