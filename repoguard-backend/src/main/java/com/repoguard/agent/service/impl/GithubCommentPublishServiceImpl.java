package com.repoguard.agent.service.impl;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.GithubCommentPreviewResponse;
import com.repoguard.agent.dto.GithubCommentPublishItem;
import com.repoguard.agent.dto.GithubCommentPublishResponse;
import com.repoguard.agent.entity.GithubCommentPublication;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubPullRequestClient;
import com.repoguard.agent.github.GithubReviewCommentDraft;
import com.repoguard.agent.github.GithubReviewCommentResult;
import com.repoguard.agent.github.GithubWritebackFailureClassifier;
import com.repoguard.agent.github.GithubWritebackFailureClassifier.FailureSummary;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchItemMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.notification.NotificationDispatchService;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.review.HumanReviewStatus;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import com.repoguard.agent.service.GithubCommentPreviewService;
import com.repoguard.agent.service.GithubCommentPublishService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
public class GithubCommentPublishServiceImpl implements GithubCommentPublishService {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private final ReviewTaskMapper reviewTaskMapper;
    private final GithubPullRequestClient githubPullRequestClient;
    private final RepoGuardMetrics metrics;
    private final NotificationDispatchService notificationDispatchService;
    private final ReviewTaskStateMachine reviewTaskStateMachine;
    private final GithubWritebackFailureClassifier writebackFailureClassifier;
    private final GithubCommentPreviewService previewService;
    private final GithubCommentPublishPlanBuilder publishPlanBuilder;
    private final GithubCommentPublicationRecorder publicationRecorder;

    public GithubCommentPublishServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        GithubCommentPublicationMapper githubCommentPublicationMapper,
        GithubCommentPublicationBatchMapper githubCommentPublicationBatchMapper,
        GithubCommentPublicationBatchItemMapper githubCommentPublicationBatchItemMapper,
        GithubPullRequestClient githubPullRequestClient,
        RepoGuardMetrics metrics,
        NotificationDispatchService notificationDispatchService,
        ReviewTaskStateMachine reviewTaskStateMachine,
        GithubWritebackFailureClassifier writebackFailureClassifier,
        GithubCommentPreviewService previewService
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.githubPullRequestClient = githubPullRequestClient;
        this.metrics = metrics;
        this.notificationDispatchService = notificationDispatchService;
        this.reviewTaskStateMachine = reviewTaskStateMachine == null
            ? new ReviewTaskStateMachine()
            : reviewTaskStateMachine;
        this.writebackFailureClassifier = writebackFailureClassifier;
        this.previewService = previewService;
        this.publishPlanBuilder = new GithubCommentPublishPlanBuilder();
        this.publicationRecorder = new GithubCommentPublicationRecorder(
            githubCommentPublicationMapper,
            githubCommentPublicationBatchMapper,
            githubCommentPublicationBatchItemMapper
        );
    }

    @Override
    public GithubCommentPublishResponse publishGithubComments(Long taskId) {
        LocalDateTime startedAt = LocalDateTime.now();
        ReviewTask task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + taskId);
        }
        ensureGithubCommentPublishAllowed(task);

        GithubCommentPreviewResponse preview = previewService.getPreview(taskId);
        GithubCommentPublishPlan publishPlan = publishPlanBuilder.build(preview);
        List<GithubReviewCommentDraft> drafts = publishPlan.drafts();
        List<GithubCommentPublishItem> skippedItems = publishPlan.skippedItems();

        List<GithubCommentPublishItem> publishedItems = publishDrafts(task, drafts);
        List<GithubCommentPublishItem> items = new java.util.ArrayList<>(publishedItems);
        items.addAll(skippedItems);

        int succeededCount = (int) publishedItems.stream().filter(GithubCommentPublishItem::success).count();
        int failedCount = publishedItems.size() - succeededCount;
        recordGithubCommentPublishMetrics(succeededCount, failedCount, skippedItems.size());
        GithubCommentPublishResponse response = new GithubCommentPublishResponse(
            task.getId(),
            preview.totalFindings(),
            drafts.size(),
            succeededCount,
            failedCount,
            skippedItems.size(),
            items
        );
        Long batchId = publicationRecorder.recordBatch(response);
        publishGithubCommentNotification(task, response, batchId);
        recordGithubCommentPublishDuration(startedAt, failedCount > 0 ? "failed" : "success");
        return response;
    }

    private void recordGithubCommentPublishDuration(LocalDateTime startedAt, String result) {
        if (metrics != null) {
            metrics.githubCommentPublishDuration(Duration.between(startedAt, LocalDateTime.now()), result);
        }
    }

    private void recordGithubCommentPublishMetrics(int succeededCount, int failedCount, int skippedCount) {
        if (metrics == null) {
            return;
        }
        for (int i = 0; i < succeededCount; i++) {
            metrics.githubCommentPublished("success");
        }
        for (int i = 0; i < failedCount; i++) {
            metrics.githubCommentPublished("failed");
        }
        for (int i = 0; i < skippedCount; i++) {
            metrics.githubCommentPublished("skipped");
        }
    }

    private List<GithubCommentPublishItem> publishDrafts(ReviewTask task, List<GithubReviewCommentDraft> drafts) {
        if (drafts.isEmpty()) {
            return List.of();
        }
        try {
            return githubPullRequestClient.publishPullRequestComments(task, drafts).stream()
                .map(result -> toGithubCommentPublishItem(task.getId(), result))
                .toList();
        } catch (RuntimeException ex) {
            String message = StringUtils.hasText(ex.getMessage()) ? ex.getMessage() : ex.getClass().getSimpleName();
            return drafts.stream()
                .map(draft -> {
                    GithubReviewCommentResult result = new GithubReviewCommentResult(
                        draft.findingId(),
                        draft.path(),
                        draft.line(),
                        draft.targetType(),
                        false,
                        "failed",
                        message,
                        null,
                        null
                    );
                    return toGithubCommentPublishItem(task.getId(), result);
                })
                .toList();
        }
    }

    private GithubCommentPublishItem toGithubCommentPublishItem(Long taskId, GithubReviewCommentResult result) {
        GithubCommentPublication publication = publicationRecorder.recordPublication(taskId, result);
        FailureSummary failureSummary = writebackFailureClassifier.classify(
            result.status(),
            result.success(),
            result.message()
        );
        return new GithubCommentPublishItem(
            result.findingId(),
            result.path(),
            result.line(),
            result.targetType(),
            result.success(),
            result.status(),
            result.message(),
            failureSummary.category(),
            failureSummary.reason(),
            failureSummary.suggestion(),
            result.url(),
            result.commentId(),
            publication.getPublishedAt() == null ? null : publication.getPublishedAt().format(DATE_TIME_FORMATTER)
        );
    }

    private void publishGithubCommentNotification(ReviewTask task, GithubCommentPublishResponse response, Long batchId) {
        if (notificationDispatchService != null) {
            notificationDispatchService.githubCommentsPublished(task, response, batchId);
        }
    }

    private void ensureGithubCommentPublishAllowed(ReviewTask task) {
        String humanReviewStatus = resolveHumanReviewStatus(task);
        if (reviewTaskStateMachine.canPublishGithubComments(
            Boolean.TRUE.equals(task.getHumanReviewRequired()),
            humanReviewStatus
        )) {
            return;
        }
        throw new BusinessException(
            ErrorCode.BAD_REQUEST,
            "Human review approval or changes request is required before publishing GitHub comments"
        );
    }

    private String resolveHumanReviewStatus(ReviewTask task) {
        if (StringUtils.hasText(task.getHumanReviewStatus())) {
            return HumanReviewStatus.from(task.getHumanReviewStatus()).code();
        }
        return HumanReviewStatus.defaultForRequired(Boolean.TRUE.equals(task.getHumanReviewRequired())).code();
    }
}
