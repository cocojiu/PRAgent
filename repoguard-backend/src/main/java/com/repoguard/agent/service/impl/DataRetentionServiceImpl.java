package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.DataRetentionProperties;
import com.repoguard.agent.config.SystemSettings;
import com.repoguard.agent.config.SystemSettingsProvider;
import com.repoguard.agent.dto.DataRetentionCleanupAuditDto;
import com.repoguard.agent.dto.DataRetentionCleanupRequest;
import com.repoguard.agent.dto.DataRetentionCleanupResponse;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.entity.GithubCommentPublication;
import com.repoguard.agent.entity.GithubCommentPublicationBatch;
import com.repoguard.agent.entity.GithubCommentPublicationBatchItem;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchItemMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskArchiveSummaryMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import com.repoguard.agent.service.DataRetentionService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DataRetentionServiceImpl implements DataRetentionService {

    private static final String CONFIRM_TEXT = "CLEANUP";
    private static final int DEFAULT_RETENTION_DAYS = 90;
    private static final int DEFAULT_MAX_TASKS = 500;
    private static final int BACKUP_REFERENCE_MAX_LENGTH = 128;
    private static final Pattern BACKUP_REFERENCE_PATTERN = Pattern.compile(
        "^backup://[A-Za-z0-9][A-Za-z0-9._-]{0,63}/[A-Za-z0-9._~:@%+/-]+$"
    );
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ReviewTaskMapper reviewTaskMapper;
    private final ChangedFileMapper changedFileMapper;
    private final ReviewFindingMapper reviewFindingMapper;
    private final ReviewTimelineMapper reviewTimelineMapper;
    private final GithubCommentPublicationMapper githubCommentPublicationMapper;
    private final GithubCommentPublicationBatchMapper githubCommentPublicationBatchMapper;
    private final GithubCommentPublicationBatchItemMapper githubCommentPublicationBatchItemMapper;
    private final ReviewTaskArchiveSummaryMapper reviewTaskArchiveSummaryMapper;
    private final SystemSettingsProvider systemSettingsProvider;
    private final ReviewTaskStateMachine reviewTaskStateMachine;
    private final DataRetentionMetricsRecorder metricsRecorder;
    private final DataRetentionCleanupAuditRecorder auditRecorder;
    private final DataRetentionCleanupAuditQueryService auditQueryService;
    private final DataRetentionCleanupLeaseStore leaseStore;
    private final DataRetentionProperties dataRetentionProperties;
    private final ReentrantLock cleanupLock = new ReentrantLock();

    @Autowired
    public DataRetentionServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ChangedFileMapper changedFileMapper,
        ReviewFindingMapper reviewFindingMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        GithubCommentPublicationMapper githubCommentPublicationMapper,
        GithubCommentPublicationBatchMapper githubCommentPublicationBatchMapper,
        GithubCommentPublicationBatchItemMapper githubCommentPublicationBatchItemMapper,
        ReviewTaskArchiveSummaryMapper reviewTaskArchiveSummaryMapper,
        SystemSettingsProvider systemSettingsProvider,
        ReviewTaskStateMachine reviewTaskStateMachine,
        DataRetentionMetricsRecorder metricsRecorder,
        DataRetentionCleanupAuditRecorder auditRecorder,
        DataRetentionCleanupAuditQueryService auditQueryService,
        DataRetentionCleanupLeaseStore leaseStore,
        DataRetentionProperties dataRetentionProperties
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.changedFileMapper = changedFileMapper;
        this.reviewFindingMapper = reviewFindingMapper;
        this.reviewTimelineMapper = reviewTimelineMapper;
        this.githubCommentPublicationMapper = githubCommentPublicationMapper;
        this.githubCommentPublicationBatchMapper = githubCommentPublicationBatchMapper;
        this.githubCommentPublicationBatchItemMapper = githubCommentPublicationBatchItemMapper;
        this.reviewTaskArchiveSummaryMapper = Objects.requireNonNull(
            reviewTaskArchiveSummaryMapper,
            "reviewTaskArchiveSummaryMapper"
        );
        this.systemSettingsProvider = systemSettingsProvider;
        this.reviewTaskStateMachine = Objects.requireNonNull(reviewTaskStateMachine, "reviewTaskStateMachine");
        this.metricsRecorder = Objects.requireNonNull(metricsRecorder, "metricsRecorder");
        this.auditRecorder = Objects.requireNonNull(auditRecorder, "auditRecorder");
        this.auditQueryService = Objects.requireNonNull(auditQueryService, "auditQueryService");
        this.leaseStore = Objects.requireNonNull(leaseStore, "leaseStore");
        this.dataRetentionProperties = Objects.requireNonNull(dataRetentionProperties, "dataRetentionProperties");
    }

    @Override
    @Transactional
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
            leaseStore.release(lease);
            cleanupLock.unlock();
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
        try {
            LambdaQueryWrapper<ReviewTask> candidateQuery = candidateTaskQuery(cutoff);
            long candidateTasks = reviewTaskMapper.selectCount(candidateQuery);
            List<Long> taskIds = reviewTaskMapper.selectList(candidateTaskQuery(cutoff)
                    .orderByAsc(ReviewTask::getCreatedAt)
                    .last("limit " + Math.max(1, maxTasks)))
                .stream()
                .map(ReviewTask::getId)
                .toList();

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
                    0,
                    0,
                    0,
                    0,
                    0,
                    0,
                    0
                ));
            }

            reviewTaskArchiveSummaryMapper.insertArchiveSummaries(cleanupBatchId, backupReference, taskIds);
            int deletedBatchItems = githubCommentPublicationBatchItemMapper.delete(
                new LambdaQueryWrapper<GithubCommentPublicationBatchItem>().in(GithubCommentPublicationBatchItem::getTaskId, taskIds)
            );
            int deletedPublications = githubCommentPublicationMapper.delete(
                new LambdaQueryWrapper<GithubCommentPublication>().in(GithubCommentPublication::getTaskId, taskIds)
            );
            int deletedBatches = githubCommentPublicationBatchMapper.delete(
                new LambdaQueryWrapper<GithubCommentPublicationBatch>().in(GithubCommentPublicationBatch::getTaskId, taskIds)
            );
            int deletedChangedFiles = changedFileMapper.delete(
                new LambdaQueryWrapper<ChangedFile>().in(ChangedFile::getTaskId, taskIds)
            );
            int deletedTimelines = reviewTimelineMapper.delete(
                new LambdaQueryWrapper<ReviewTimeline>().in(ReviewTimeline::getTaskId, taskIds)
            );
            int deletedFindings = reviewFindingMapper.delete(
                new LambdaQueryWrapper<ReviewFinding>().in(ReviewFinding::getTaskId, taskIds)
            );
            int deletedTasks = reviewTaskMapper.delete(
                new LambdaQueryWrapper<ReviewTask>().in(ReviewTask::getId, taskIds)
            );

            return completed(cleanupBatchId, response(
                true,
                cleanupBatchId,
                retentionDays,
                maxTasks,
                backupReference,
                cutoff,
                candidateTasks,
                taskIds.size(),
                deletedBatchItems,
                deletedPublications,
                deletedBatches,
                deletedChangedFiles,
                deletedTimelines,
                deletedFindings,
                deletedTasks
            ));
        } catch (RuntimeException ex) {
            auditRecorder.fail(cleanupBatchId, ex);
            throw ex;
        }
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

    private LambdaQueryWrapper<ReviewTask> candidateTaskQuery(LocalDateTime cutoff) {
        return new LambdaQueryWrapper<ReviewTask>()
            .lt(ReviewTask::getCreatedAt, cutoff)
            .in(ReviewTask::getStatus, reviewTaskStateMachine.dataRetentionCandidateStatuses());
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
        int deletedBatchItems,
        int deletedPublications,
        int deletedBatches,
        int deletedChangedFiles,
        int deletedTimelines,
        int deletedFindings,
        int deletedTasks
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
            deletedBatchItems,
            deletedPublications,
            deletedBatches,
            deletedChangedFiles,
            deletedTimelines,
            deletedFindings,
            deletedTasks
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
}
