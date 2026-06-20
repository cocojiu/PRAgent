package com.repoguard.agent.service.impl;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.GithubCommentPreviewResponse;
import com.repoguard.agent.dto.GithubCommentPublishItem;
import com.repoguard.agent.dto.GithubCommentPublishResponse;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubPullRequestClient;
import com.repoguard.agent.github.GithubReviewCommentDraft;
import com.repoguard.agent.github.GithubWritebackFailureClassifier;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchItemMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationBatchMapper;
import com.repoguard.agent.mapper.GithubCommentPublicationMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.notification.NotificationDispatchService;
import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import com.repoguard.agent.service.GithubCommentPreviewService;
import com.repoguard.agent.service.GithubCommentPublishService;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class GithubCommentPublishServiceImpl implements GithubCommentPublishService {

    private final ReviewTaskMapper reviewTaskMapper;
    private final RepoGuardMetrics metrics;
    private final NotificationDispatchService notificationDispatchService;
    private final GithubCommentPreviewService previewService;
    private final GithubCommentPublishGuard publishGuard;
    private final GithubCommentPublishPlanBuilder publishPlanBuilder;
    private final GithubCommentDraftPublisher draftPublisher;
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
        this(
            reviewTaskMapper,
            metrics,
            notificationDispatchService,
            previewService,
            new GithubCommentPublishGuard(reviewTaskStateMachine),
            new GithubCommentPublishPlanBuilder(),
            new GithubCommentDraftPublisher(
                githubPullRequestClient,
                new GithubCommentPublicationRecorder(
                    githubCommentPublicationMapper,
                    githubCommentPublicationBatchMapper,
                    githubCommentPublicationBatchItemMapper
                ),
                writebackFailureClassifier
            ),
            new GithubCommentPublicationRecorder(
                githubCommentPublicationMapper,
                githubCommentPublicationBatchMapper,
                githubCommentPublicationBatchItemMapper
            )
        );
    }

    @Autowired
    public GithubCommentPublishServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        RepoGuardMetrics metrics,
        NotificationDispatchService notificationDispatchService,
        GithubCommentPreviewService previewService,
        GithubCommentPublishGuard publishGuard,
        GithubCommentPublishPlanBuilder publishPlanBuilder,
        GithubCommentDraftPublisher draftPublisher,
        GithubCommentPublicationRecorder publicationRecorder
    ) {
        this.reviewTaskMapper = reviewTaskMapper;
        this.metrics = metrics;
        this.notificationDispatchService = notificationDispatchService;
        this.previewService = previewService;
        this.publishGuard = publishGuard;
        this.publishPlanBuilder = publishPlanBuilder;
        this.draftPublisher = draftPublisher;
        this.publicationRecorder = publicationRecorder;
    }

    @Override
    public GithubCommentPublishResponse publishGithubComments(Long taskId) {
        LocalDateTime startedAt = LocalDateTime.now();
        ReviewTask task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + taskId);
        }
        publishGuard.ensurePublishAllowed(task);

        GithubCommentPreviewResponse preview = previewService.getPreview(taskId);
        GithubCommentPublishPlan publishPlan = publishPlanBuilder.build(preview);
        List<GithubReviewCommentDraft> drafts = publishPlan.drafts();
        List<GithubCommentPublishItem> skippedItems = publishPlan.skippedItems();

        List<GithubCommentPublishItem> publishedItems = draftPublisher.publish(task, drafts);
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

    private void publishGithubCommentNotification(ReviewTask task, GithubCommentPublishResponse response, Long batchId) {
        if (notificationDispatchService != null) {
            notificationDispatchService.githubCommentsPublished(task, response, batchId);
        }
    }

}
