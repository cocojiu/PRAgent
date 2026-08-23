package com.repoguard.agent.review.execution;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.ReviewAttemptChangedFileDto;
import com.repoguard.agent.dto.ReviewAttemptFindingDto;
import com.repoguard.agent.dto.ReviewExecutionAttemptDto;
import com.repoguard.agent.dto.ReviewExecutionAttemptResultDto;
import com.repoguard.agent.dto.PageResponse;
import com.repoguard.agent.entity.ChangedFile;
import com.repoguard.agent.entity.ReviewExecutionAttempt;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ChangedFileMapper;
import com.repoguard.agent.mapper.ReviewExecutionAttemptMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReviewExecutionAttemptQueryService {

    private static final int MAX_ATTEMPTS = 100;

    private final ReviewTaskMapper taskMapper;
    private final ReviewExecutionAttemptMapper attemptMapper;
    private final ChangedFileMapper changedFileMapper;
    private final ReviewFindingMapper findingMapper;

    public ReviewExecutionAttemptQueryService(
        ReviewTaskMapper taskMapper,
        ReviewExecutionAttemptMapper attemptMapper,
        ChangedFileMapper changedFileMapper,
        ReviewFindingMapper findingMapper
    ) {
        this.taskMapper = Objects.requireNonNull(taskMapper, "taskMapper must not be null");
        this.attemptMapper = Objects.requireNonNull(attemptMapper, "attemptMapper must not be null");
        this.changedFileMapper = Objects.requireNonNull(changedFileMapper, "changedFileMapper must not be null");
        this.findingMapper = Objects.requireNonNull(findingMapper, "findingMapper must not be null");
    }

    @Transactional(readOnly = true)
    public List<ReviewExecutionAttemptDto> list(Long taskId) {
        ReviewTask task = requireTask(taskId);
        return attemptMapper.selectByTaskId(taskId, MAX_ATTEMPTS).stream()
            .map(attempt -> toDto(attempt, task.getCurrentAttemptId()))
            .toList();
    }

    @Transactional(readOnly = true)
    public ReviewExecutionAttemptResultDto getResult(Long taskId, Long attemptId, int page, int pageSize) {
        ReviewTask task = requireTask(taskId);
        ReviewExecutionAttempt attempt = attemptMapper.selectById(attemptId);
        if (attempt == null || !Objects.equals(taskId, attempt.getTaskId())) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review execution attempt not found: " + attemptId);
        }
        Page<ChangedFile> changedFilePage = changedFileMapper.selectPage(
            Page.of(page, pageSize),
            new LambdaQueryWrapper<ChangedFile>()
                .eq(ChangedFile::getAttemptId, attemptId)
                .orderByAsc(ChangedFile::getId)
        );
        Page<ReviewFinding> findingPage = findingMapper.selectPage(
            Page.of(page, pageSize),
            new LambdaQueryWrapper<ReviewFinding>()
                .eq(ReviewFinding::getAttemptId, attemptId)
                .orderByAsc(ReviewFinding::getId)
        );
        return new ReviewExecutionAttemptResultDto(
            toDto(attempt, task.getCurrentAttemptId()),
            new PageResponse<>(
                changedFilePage.getRecords().stream().map(ReviewExecutionAttemptQueryService::toChangedFileDto).toList(),
                changedFilePage.getTotal()
            ),
            new PageResponse<>(
                findingPage.getRecords().stream().map(ReviewExecutionAttemptQueryService::toFindingDto).toList(),
                findingPage.getTotal()
            )
        );
    }

    private ReviewTask requireTask(Long taskId) {
        ReviewTask task = taskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + taskId);
        }
        return task;
    }

    private static ReviewExecutionAttemptDto toDto(ReviewExecutionAttempt attempt, Long currentAttemptId) {
        return new ReviewExecutionAttemptDto(
            attempt.getId(),
            attempt.getTaskId(),
            attempt.getAttemptNo(),
            attempt.getGeneration(),
            attempt.getCommitSha(),
            attempt.getInputFingerprint(),
            attempt.getWorkerId(),
            attempt.getStatus(),
            attempt.getFailureCategory(),
            attempt.getBudgetExhaustedStage(),
            attempt.getPolicyVersion(),
            attempt.getPromptVersion(),
            attempt.getContextVersion(),
            attempt.getSchemaVersion(),
            attempt.getVerifierVersion(),
            attempt.getAggregationVersion(),
            attempt.getDiffFetchMs(),
            attempt.getReviewMs(),
            attempt.getPersistMs(),
            attempt.getTotalMs(),
            attempt.getPromptTokens(),
            attempt.getCompletionTokens(),
            attempt.getTotalTokens(),
            attempt.getEstimatedCost(),
            attempt.getQueuedAt(),
            attempt.getStartedAt(),
            attempt.getFinishedAt(),
            attempt.getPayloadPurgedAt(),
            Objects.equals(currentAttemptId, attempt.getId())
        );
    }

    private static ReviewAttemptChangedFileDto toChangedFileDto(ChangedFile file) {
        return new ReviewAttemptChangedFileDto(
            file.getId(),
            file.getFilePath(),
            file.getChangeType(),
            file.getAdditions(),
            file.getDeletions()
        );
    }

    private static ReviewAttemptFindingDto toFindingDto(ReviewFinding finding) {
        return new ReviewAttemptFindingDto(
            finding.getId(),
            finding.getCategory(),
            finding.getSeverity(),
            finding.getSource(),
            finding.getRuleId(),
            finding.getFilePath(),
            finding.getLineNumber(),
            finding.getMessage(),
            finding.getRecommendation(),
            finding.getConfidence(),
            finding.getIsBlocking(),
            finding.getFeedbackStatus(),
            finding.getPromptVersion(),
            finding.getContextVersion(),
            finding.getSchemaVersion(),
            finding.getVerifierVersion(),
            finding.getAggregationVersion()
        );
    }
}
