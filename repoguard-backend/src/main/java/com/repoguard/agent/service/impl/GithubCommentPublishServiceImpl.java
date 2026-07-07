package com.repoguard.agent.service.impl;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.GithubCommentPreviewResponse;
import com.repoguard.agent.dto.GithubCommentPublishItem;
import com.repoguard.agent.dto.GithubCommentPublishResponse;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubReviewCommentDraft;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.notification.NotificationDispatchService;
import com.repoguard.agent.service.GithubCommentPreviewService;
import com.repoguard.agent.service.GithubCommentPublishService;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class GithubCommentPublishServiceImpl implements GithubCommentPublishService {

    private final ReviewTaskMapper reviewTaskMapper;
    private final GithubCommentPublishMetricsRecorder metricsRecorder;
    private final NotificationDispatchService notificationDispatchService;
    private final GithubCommentPreviewService previewService;
    private final GithubCommentPublishGuard publishGuard;
    private final GithubCommentPublishPlanBuilder publishPlanBuilder;
    private final GithubCommentDraftPublisher draftPublisher;
    private final GithubCommentPublicationRecorder publicationRecorder;

    @Autowired
    public GithubCommentPublishServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        GithubCommentPublishMetricsRecorder metricsRecorder,
        NotificationDispatchService notificationDispatchService,
        GithubCommentPreviewService previewService,
        GithubCommentPublishGuard publishGuard,
        GithubCommentPublishPlanBuilder publishPlanBuilder,
        GithubCommentDraftPublisher draftPublisher,
        GithubCommentPublicationRecorder publicationRecorder
    ) {
        this.reviewTaskMapper = Objects.requireNonNull(reviewTaskMapper, "reviewTaskMapper");
        this.metricsRecorder = Objects.requireNonNull(metricsRecorder, "metricsRecorder");
        this.notificationDispatchService = Objects.requireNonNull(notificationDispatchService, "notificationDispatchService");
        this.previewService = Objects.requireNonNull(previewService, "previewService");
        this.publishGuard = Objects.requireNonNull(publishGuard, "publishGuard");
        this.publishPlanBuilder = Objects.requireNonNull(publishPlanBuilder, "publishPlanBuilder");
        this.draftPublisher = Objects.requireNonNull(draftPublisher, "draftPublisher");
        this.publicationRecorder = Objects.requireNonNull(publicationRecorder, "publicationRecorder");
    }

    @Override
    public GithubCommentPublishResponse publishGithubComments(Long taskId) {
        LocalDateTime startedAt = LocalDateTime.now();
        ReviewTask task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + taskId);
        }
        publishGuard.ensurePublishAllowed(task);

        GithubCommentPreviewResponse preview = previewService.getFullPreview(taskId);
        GithubCommentPublishPlan publishPlan = publishPlanBuilder.build(preview);
        List<GithubReviewCommentDraft> drafts = publishPlan.drafts();
        List<GithubCommentPublishItem> skippedItems = publishPlan.skippedItems();

        List<GithubCommentPublishItem> publishedItems = draftPublisher.publish(task, drafts);
        List<GithubCommentPublishItem> items = new java.util.ArrayList<>(publishedItems);
        items.addAll(skippedItems);

        int succeededCount = (int) publishedItems.stream()
            .filter(item -> Boolean.TRUE.equals(item.success()))
            .count();
        int failedCount = publishedItems.size() - succeededCount;
        metricsRecorder.recordItems(succeededCount, failedCount, skippedItems.size());
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
        metricsRecorder.recordDuration(startedAt, failedCount > 0);
        return response;
    }

    private void publishGithubCommentNotification(ReviewTask task, GithubCommentPublishResponse response, Long batchId) {
        notificationDispatchService.githubCommentsPublished(task, response, batchId);
    }

}
