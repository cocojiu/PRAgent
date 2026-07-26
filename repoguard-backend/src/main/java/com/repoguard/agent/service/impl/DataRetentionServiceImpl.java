package com.repoguard.agent.service.impl;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.DataRetentionProperties;
import com.repoguard.agent.config.SystemSettings;
import com.repoguard.agent.config.SystemSettingsProvider;
import com.repoguard.agent.dto.DataRetentionCleanupAuditDto;
import com.repoguard.agent.dto.DataRetentionCleanupRequest;
import com.repoguard.agent.dto.DataRetentionCleanupResponse;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.retention.DataRetentionCandidateQuery;
import com.repoguard.agent.retention.DataRetentionDeleteExecutor;
import com.repoguard.agent.service.DataRetentionService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class DataRetentionServiceImpl implements DataRetentionService {

    private static final String CONFIRM_TEXT = "CLEANUP";
    private static final int DEFAULT_RETENTION_DAYS = 90;
    private static final int DEFAULT_MAX_TASKS = 500;
    private static final int CLEANUP_SLICE_SIZE = 50;
    private static final int BACKUP_REFERENCE_MAX_LENGTH = 128;
    private static final Pattern BACKUP_REFERENCE_PATTERN = Pattern.compile(
        "^backup://[A-Za-z0-9][A-Za-z0-9._-]{0,63}/[A-Za-z0-9._~:@%+/-]+$"
    );
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DataRetentionDeleteExecutor.DeletionResult EMPTY_DELETION =
        new DataRetentionDeleteExecutor.DeletionResult(0, 0, 0, 0, 0, 0, 0);

    private final DataRetentionCandidateQuery candidateQuery;
    private final DataRetentionCleanupSliceExecutor sliceExecutor;
    private final SystemSettingsProvider systemSettingsProvider;
    private final DataRetentionMetricsRecorder metricsRecorder;
    private final DataRetentionCleanupAuditRecorder auditRecorder;
    private final DataRetentionCleanupAuditQueryService auditQueryService;
    private final DataRetentionCleanupLeaseStore leaseStore;
    private final DataRetentionProperties dataRetentionProperties;
    private final ReentrantLock cleanupLock = new ReentrantLock();

    @Autowired
    public DataRetentionServiceImpl(
        DataRetentionCandidateQuery candidateQuery,
        DataRetentionCleanupSliceExecutor sliceExecutor,
        SystemSettingsProvider systemSettingsProvider,
        DataRetentionMetricsRecorder metricsRecorder,
        DataRetentionCleanupAuditRecorder auditRecorder,
        DataRetentionCleanupAuditQueryService auditQueryService,
        DataRetentionCleanupLeaseStore leaseStore,
        DataRetentionProperties dataRetentionProperties
    ) {
        this.candidateQuery = Objects.requireNonNull(candidateQuery, "candidateQuery");
        this.sliceExecutor = Objects.requireNonNull(sliceExecutor, "sliceExecutor");
        this.systemSettingsProvider = systemSettingsProvider;
        this.metricsRecorder = Objects.requireNonNull(metricsRecorder, "metricsRecorder");
        this.auditRecorder = Objects.requireNonNull(auditRecorder, "auditRecorder");
        this.auditQueryService = Objects.requireNonNull(auditQueryService, "auditQueryService");
        this.leaseStore = Objects.requireNonNull(leaseStore, "leaseStore");
        this.dataRetentionProperties = Objects.requireNonNull(dataRetentionProperties, "dataRetentionProperties");
    }

    @Override
    public DataRetentionCleanupResponse cleanup(DataRetentionCleanupRequest request) {
        boolean execute = request != null && Boolean.TRUE.equals(request.execute());
        if (!cleanupLock.tryLock()) {
            BusinessException ex = new BusinessException(
                ErrorCode.BAD_REQUEST,
                "已有数据保留清理任务正在执行，请稍后再试。"
            );
            metricsRecorder.recordFailure(execute, ex);
            throw ex;
        }
        DataRetentionCleanupLeaseStore.Lease lease = null;
        try {
            validateExecutionGuards(request, execute);
            lease = acquireLease();
            return cleanupInternal(request, execute);
        } catch (RuntimeException ex) {
            metricsRecorder.recordFailure(execute, ex);
            throw ex;
        } finally {
            releaseCleanupResources(lease);
        }
    }

    private void validateExecutionGuards(DataRetentionCleanupRequest request, boolean execute) {
        if (!execute) {
            return;
        }
        if (request == null || request.confirmText() == null || !CONFIRM_TEXT.equals(request.confirmText().trim())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "执行数据清理时必须提供确认短语 CLEANUP。");
        }
        String backupReference = normalizeBlankToNull(request.backupReference());
        if (backupReference == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "执行数据清理时必须提供备份凭证 backupReference。");
        }
        validateBackupReference(backupReference);
        validateExecutionMaxTasks(request);
    }

    private DataRetentionCleanupLeaseStore.Lease acquireLease() {
        DataRetentionCleanupLeaseStore.Lease lease = leaseStore.acquire();
        if (lease != null) {
            return lease;
        }
        BusinessException ex = new BusinessException(
            ErrorCode.BAD_REQUEST,
            "已有数据保留清理任务正在其它实例执行，请稍后再试。"
        );
        throw ex;
    }

    @Override
    public PageResponse<DataRetentionCleanupAuditDto> listCleanupAudits(
        int page,
        int pageSize,
        String mode,
        String status,
        String backupReference
    ) {
        return auditQueryService.listAudits(page, pageSize, mode, status, backupReference);
    }

    private DataRetentionCleanupResponse cleanupInternal(DataRetentionCleanupRequest request, boolean execute) {
        int retentionDays = resolveRetentionDays(request);
        int maxTasks = resolveMaxTasks(request, execute);
        if (execute && (request.confirmText() == null || !CONFIRM_TEXT.equals(request.confirmText().trim()))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "执行数据清理时必须提供确认短语 CLEANUP。");
        }
        String backupReference = resolveBackupReference(request, execute);

        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        Long cleanupBatchId = auditRecorder.start(execute, retentionDays, maxTasks, backupReference, cutoff);
        SliceProgress progress = null;
        try {
            DataRetentionCandidateQuery.CandidateSelection candidates = candidateQuery.select(cutoff, maxTasks);
            long candidateTasks = candidates.candidateTasks();
            List<Long> taskIds = candidates.taskIds();

            if (!execute || taskIds.isEmpty()) {
                return completed(cleanupBatchId, response(
                    false,
                    cleanupBatchId,
                    retentionDays,
                    maxTasks,
                    backupReference,
                    cutoff,
                    candidateTasks,
                    taskIds.size(),
                    EMPTY_DELETION
                ));
            }

            List<List<Long>> slices = partition(taskIds);
            progress = new SliceProgress(candidateTasks, taskIds.size(), slices.size());
            for (List<Long> slice : slices) {
                progress.accumulate(sliceExecutor.archiveAndDelete(cleanupBatchId, backupReference, slice));
            }

            return completed(cleanupBatchId, response(
                true,
                cleanupBatchId,
                retentionDays,
                maxTasks,
                backupReference,
                cutoff,
                candidateTasks,
                taskIds.size(),
                progress.deletion
            ));
        } catch (RuntimeException ex) {
            if (progress == null) {
                auditRecorder.fail(cleanupBatchId, ex);
            } else {
                auditRecorder.fail(
                    cleanupBatchId,
                    ex,
                    progress.candidateTasks,
                    progress.selectedTasks,
                    progress.completedSlices,
                    progress.totalSlices,
                    progress.deletion
                );
            }
            throw ex;
        }
    }

    private List<List<Long>> partition(List<Long> taskIds) {
        List<List<Long>> slices = new ArrayList<>();
        for (int from = 0; from < taskIds.size(); from += CLEANUP_SLICE_SIZE) {
            slices.add(taskIds.subList(from, Math.min(from + CLEANUP_SLICE_SIZE, taskIds.size())));
        }
        return slices;
    }

    private int resolveRetentionDays(DataRetentionCleanupRequest request) {
        if (request != null && request.retentionDays() != null) {
            return request.retentionDays();
        }
        SystemSettings settings = systemSettingsProvider.getSettings();
        Integer retentionDays = settings == null ? null : settings.retentionDays();
        return retentionDays == null || retentionDays <= 0 ? DEFAULT_RETENTION_DAYS : retentionDays;
    }

    private int resolveMaxTasks(DataRetentionCleanupRequest request, boolean execute) {
        Integer requestedMaxTasks = request == null ? null : request.maxTasks();
        if (!execute) {
            return requestedMaxTasks == null ? DEFAULT_MAX_TASKS : requestedMaxTasks;
        }
        int cleanupMaxTasksPerRun = dataRetentionProperties.normalizedCleanupMaxTasksPerRun();
        if (requestedMaxTasks == null) {
            return Math.min(DEFAULT_MAX_TASKS, cleanupMaxTasksPerRun);
        }
        return requestedMaxTasks;
    }

    private void validateExecutionMaxTasks(DataRetentionCleanupRequest request) {
        Integer requestedMaxTasks = request == null ? null : request.maxTasks();
        int cleanupMaxTasksPerRun = dataRetentionProperties.normalizedCleanupMaxTasksPerRun();
        if (requestedMaxTasks != null && requestedMaxTasks > cleanupMaxTasksPerRun) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "执行数据清理时 maxTasks 不能超过当前生产上限 " + cleanupMaxTasksPerRun + "。"
            );
        }
    }

    private String resolveBackupReference(DataRetentionCleanupRequest request, boolean execute) {
        String backupReference = normalizeBlankToNull(request == null ? null : request.backupReference());
        if (execute && backupReference == null) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "执行数据清理时必须提供备份凭证 backupReference。");
        }
        return backupReference;
    }

    private void validateBackupReference(String backupReference) {
        if (backupReference.length() > BACKUP_REFERENCE_MAX_LENGTH
            || containsControlOrWhitespace(backupReference)
            || !BACKUP_REFERENCE_PATTERN.matcher(backupReference).matches()
            || containsUnsafePathSegment(backupReference)
        ) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "备份凭证 backupReference 必须使用 backup://<provider>/<path> 格式，并指向已完成的生产备份。"
            );
        }
    }

    private boolean containsControlOrWhitespace(String value) {
        return value.chars().anyMatch(character -> Character.isWhitespace(character) || Character.isISOControl(character));
    }

    private boolean containsUnsafePathSegment(String backupReference) {
        String path = backupReference.substring(backupReference.indexOf('/', "backup://".length()));
        for (String segment : path.split("/")) {
            if (".".equals(segment) || "..".equals(segment)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeBlankToNull(String value) {
        if (value == null || value.trim().isEmpty()) {
            return null;
        }
        return value.trim();
    }

    private DataRetentionCleanupResponse response(
        boolean executed,
        long cleanupBatchId,
        int retentionDays,
        int maxTasks,
        String backupReference,
        LocalDateTime cutoff,
        long candidateTasks,
        int selectedTasks,
        DataRetentionDeleteExecutor.DeletionResult deletion
    ) {
        return new DataRetentionCleanupResponse(
            executed,
            cleanupBatchId,
            retentionDays,
            maxTasks,
            backupReference,
            cutoff.format(DATE_TIME_FORMATTER),
            candidateTasks,
            selectedTasks,
            deletion.deletedBatchItems(),
            deletion.deletedPublications(),
            deletion.deletedBatches(),
            deletion.deletedChangedFiles(),
            deletion.deletedTimelines(),
            deletion.deletedFindings(),
            deletion.deletedTasks()
        );
    }

    private DataRetentionCleanupResponse recorded(DataRetentionCleanupResponse response) {
        metricsRecorder.record(response);
        return response;
    }

    private DataRetentionCleanupResponse completed(Long cleanupBatchId, DataRetentionCleanupResponse response) {
        auditRecorder.complete(cleanupBatchId, response);
        return recorded(response);
    }

    private void releaseCleanupResources(DataRetentionCleanupLeaseStore.Lease lease) {
        try {
            leaseStore.release(lease);
        } finally {
            cleanupLock.unlock();
        }
    }

    private static final class SliceProgress {

        private final long candidateTasks;
        private final int selectedTasks;
        private final int totalSlices;
        private int completedSlices;
        private DataRetentionDeleteExecutor.DeletionResult deletion = EMPTY_DELETION;

        private SliceProgress(long candidateTasks, int selectedTasks, int totalSlices) {
            this.candidateTasks = candidateTasks;
            this.selectedTasks = selectedTasks;
            this.totalSlices = totalSlices;
        }

        private void accumulate(DataRetentionDeleteExecutor.DeletionResult sliceDeletion) {
            completedSlices++;
            deletion = new DataRetentionDeleteExecutor.DeletionResult(
                deletion.deletedBatchItems() + sliceDeletion.deletedBatchItems(),
                deletion.deletedPublications() + sliceDeletion.deletedPublications(),
                deletion.deletedBatches() + sliceDeletion.deletedBatches(),
                deletion.deletedChangedFiles() + sliceDeletion.deletedChangedFiles(),
                deletion.deletedTimelines() + sliceDeletion.deletedTimelines(),
                deletion.deletedFindings() + sliceDeletion.deletedFindings(),
                deletion.deletedTasks() + sliceDeletion.deletedTasks()
            );
        }
    }
}
