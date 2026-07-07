package com.repoguard.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.SystemSettings;
import com.repoguard.agent.config.SystemSettingsProvider;
import com.repoguard.agent.dto.DataRetentionCleanupRequest;
import com.repoguard.agent.dto.DataRetentionCleanupResponse;
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
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import com.repoguard.agent.service.DataRetentionService;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DataRetentionServiceImpl implements DataRetentionService {

    private static final String CONFIRM_TEXT = "CLEANUP";
    private static final int DEFAULT_RETENTION_DAYS = 90;
    private static final int DEFAULT_MAX_TASKS = 500;
    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final ReviewTaskMapper reviewTaskMapper;
    private final ChangedFileMapper changedFileMapper;
    private final ReviewFindingMapper reviewFindingMapper;
    private final ReviewTimelineMapper reviewTimelineMapper;
    private final GithubCommentPublicationMapper githubCommentPublicationMapper;
    private final GithubCommentPublicationBatchMapper githubCommentPublicationBatchMapper;
    private final GithubCommentPublicationBatchItemMapper githubCommentPublicationBatchItemMapper;
    private final SystemSettingsProvider systemSettingsProvider;
    private final ReviewTaskStateMachine reviewTaskStateMachine;
    private final DataRetentionMetricsRecorder metricsRecorder;

    @Autowired
    public DataRetentionServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        ChangedFileMapper changedFileMapper,
        ReviewFindingMapper reviewFindingMapper,
        ReviewTimelineMapper reviewTimelineMapper,
        GithubCommentPublicationMapper githubCommentPublicationMapper,
        GithubCommentPublicationBatchMapper githubCommentPublicationBatchMapper,
        GithubCommentPublicationBatchItemMapper githubCommentPublicationBatchItemMapper,
        SystemSettingsProvider systemSettingsProvider,
        ReviewTaskStateMachine reviewTaskStateMachine,
        DataRetentionMetricsRecorder metricsRecorder
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.changedFileMapper = changedFileMapper;
        this.reviewFindingMapper = reviewFindingMapper;
        this.reviewTimelineMapper = reviewTimelineMapper;
        this.githubCommentPublicationMapper = githubCommentPublicationMapper;
        this.githubCommentPublicationBatchMapper = githubCommentPublicationBatchMapper;
        this.githubCommentPublicationBatchItemMapper = githubCommentPublicationBatchItemMapper;
        this.systemSettingsProvider = systemSettingsProvider;
        this.reviewTaskStateMachine = Objects.requireNonNull(reviewTaskStateMachine, "reviewTaskStateMachine");
        this.metricsRecorder = Objects.requireNonNull(metricsRecorder, "metricsRecorder");
    }

    @Override
    @Transactional
    public DataRetentionCleanupResponse cleanup(DataRetentionCleanupRequest request) {
        int retentionDays = resolveRetentionDays(request);
        int maxTasks = request != null && request.maxTasks() != null ? request.maxTasks() : DEFAULT_MAX_TASKS;
        boolean execute = request != null && Boolean.TRUE.equals(request.execute());
        if (execute && (request.confirmText() == null || !CONFIRM_TEXT.equals(request.confirmText().trim()))) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "执行数据清理时必须提供确认短语 CLEANUP。");
        }

        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);
        LambdaQueryWrapper<ReviewTask> candidateQuery = candidateTaskQuery(cutoff);
        long candidateTasks = reviewTaskMapper.selectCount(candidateQuery);
        List<Long> taskIds = reviewTaskMapper.selectList(candidateTaskQuery(cutoff)
                .orderByAsc(ReviewTask::getCreatedAt)
                .last("limit " + Math.max(1, maxTasks)))
            .stream()
            .map(ReviewTask::getId)
            .toList();

        if (!execute || taskIds.isEmpty()) {
            return recorded(response(
                false,
                retentionDays,
                maxTasks,
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

        return recorded(response(
            true,
            retentionDays,
            maxTasks,
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
    }

    private int resolveRetentionDays(DataRetentionCleanupRequest request) {
        if (request != null && request.retentionDays() != null) {
            return request.retentionDays();
        }
        SystemSettings settings = systemSettingsProvider.getSettings();
        Integer retentionDays = settings == null ? null : settings.retentionDays();
        return retentionDays == null || retentionDays <= 0 ? DEFAULT_RETENTION_DAYS : retentionDays;
    }

    private LambdaQueryWrapper<ReviewTask> candidateTaskQuery(LocalDateTime cutoff) {
        return new LambdaQueryWrapper<ReviewTask>()
            .lt(ReviewTask::getCreatedAt, cutoff)
            .in(ReviewTask::getStatus, reviewTaskStateMachine.dataRetentionCandidateStatuses());
    }

    private DataRetentionCleanupResponse response(
        boolean executed,
        int retentionDays,
        int maxTasks,
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
            retentionDays,
            maxTasks,
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
}
