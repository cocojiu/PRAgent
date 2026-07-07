package com.repoguard.agent.service.impl;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.GithubCommentPreviewItem;
import com.repoguard.agent.dto.GithubCommentPublishItem;
import com.repoguard.agent.dto.GithubCommentPublishResponse;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubReviewCommentDraft;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.notification.NotificationDispatchService;
import com.repoguard.agent.service.GithubCommentPublishService;
import com.repoguard.agent.service.impl.GithubCommentPublishCandidateLoader.GithubCommentPublishCandidateOverview;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class GithubCommentPublishServiceImpl implements GithubCommentPublishService {

    private static final int PUBLISH_CANDIDATE_BATCH_SIZE = 50;

    private final ReviewTaskMapper reviewTaskMapper;
    private final GithubCommentPublishMetricsRecorder metricsRecorder;
    private final NotificationDispatchService notificationDispatchService;
    private final GithubCommentPublishCandidateLoader publishCandidateLoader;
    private final GithubCommentPublishGuard publishGuard;
    private final GithubCommentPublishPlanBuilder publishPlanBuilder;
    private final GithubCommentDraftPublisher draftPublisher;
    private final GithubCommentPublicationRecorder publicationRecorder;

    @Autowired
    public GithubCommentPublishServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        GithubCommentPublishMetricsRecorder metricsRecorder,
        NotificationDispatchService notificationDispatchService,
        GithubCommentPublishCandidateLoader publishCandidateLoader,
        GithubCommentPublishGuard publishGuard,
        GithubCommentPublishPlanBuilder publishPlanBuilder,
        GithubCommentDraftPublisher draftPublisher,
        GithubCommentPublicationRecorder publicationRecorder
    ) {
        this.reviewTaskMapper = Objects.requireNonNull(reviewTaskMapper, "reviewTaskMapper");
        this.metricsRecorder = Objects.requireNonNull(metricsRecorder, "metricsRecorder");
        this.notificationDispatchService = Objects.requireNonNull(notificationDispatchService, "notificationDispatchService");
        this.publishCandidateLoader = Objects.requireNonNull(publishCandidateLoader, "publishCandidateLoader");
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

        GithubCommentPublishCandidateOverview overview = publishCandidateLoader.loadOverview(task);
        List<GithubCommentPublishItem> items = new ArrayList<>();
        PublishCounter counter = publishCandidatesInBatches(task, overview, items);
        int skippedCount = Math.max(counter.skippedItems(), totalPublishCandidates(overview) - counter.attemptedCount());
        metricsRecorder.recordItems(counter.succeededCount(), counter.failedCount(), skippedCount);
        GithubCommentPublishResponse response = new GithubCommentPublishResponse(
            task.getId(),
            overview.totalFindings(),
            counter.attemptedCount(),
            counter.succeededCount(),
            counter.failedCount(),
            skippedCount,
            items
        );
        Long batchId = publicationRecorder.recordBatch(response);
        publishGithubCommentNotification(task, response, batchId);
        metricsRecorder.recordDuration(startedAt, counter.failedCount() > 0);
        return response;
    }

    private PublishCounter publishCandidatesInBatches(
        ReviewTask task,
        GithubCommentPublishCandidateOverview overview,
        List<GithubCommentPublishItem> items
    ) {
        PublishCounter counter = new PublishCounter();
        long lastFindingId = 0L;
        boolean firstBatch = true;
        while (true) {
            List<GithubCommentPreviewItem> candidateItems = new ArrayList<>(PUBLISH_CANDIDATE_BATCH_SIZE);
            if (firstBatch && overview.prSummaryCandidate() != null) {
                candidateItems.add(overview.prSummaryCandidate());
            }
            int findingLimit = PUBLISH_CANDIDATE_BATCH_SIZE - candidateItems.size();
            List<GithubCommentPreviewItem> findingCandidates = publishCandidateLoader.loadFindingCandidates(
                task.getId(),
                lastFindingId,
                findingLimit
            );
            if (!findingCandidates.isEmpty()) {
                lastFindingId = findingCandidates.stream()
                    .map(GithubCommentPreviewItem::findingId)
                    .filter(Objects::nonNull)
                    .mapToLong(Long::longValue)
                    .max()
                    .orElse(lastFindingId);
                candidateItems.addAll(findingCandidates);
            }
            firstBatch = false;
            if (candidateItems.isEmpty()) {
                break;
            }
            counter.add(publishCandidateItems(task, candidateItems, items));
            if (findingCandidates.size() < findingLimit) {
                break;
            }
        }
        return counter;
    }

    private PublishCounter publishCandidateItems(
        ReviewTask task,
        List<GithubCommentPreviewItem> candidateItems,
        List<GithubCommentPublishItem> items
    ) {
        GithubCommentPublishPlan publishPlan = publishPlanBuilder.build(candidateItems);
        List<GithubReviewCommentDraft> drafts = publishPlan.drafts();
        List<GithubCommentPublishItem> skippedItems = publishPlan.skippedItems();
        List<GithubCommentPublishItem> publishedItems = draftPublisher.publish(task, drafts);
        items.addAll(publishedItems);
        items.addAll(skippedItems);

        int succeededCount = (int) publishedItems.stream()
            .filter(item -> Boolean.TRUE.equals(item.success()))
            .count();
        int failedCount = publishedItems.size() - succeededCount;
        return new PublishCounter(drafts.size(), succeededCount, failedCount, skippedItems.size());
    }

    private int totalPublishCandidates(GithubCommentPublishCandidateOverview overview) {
        return Math.max(0, overview.totalFindings()) + 1;
    }

    private void publishGithubCommentNotification(ReviewTask task, GithubCommentPublishResponse response, Long batchId) {
        notificationDispatchService.githubCommentsPublished(task, response, batchId);
    }

    private static final class PublishCounter {
        private int attemptedCount;
        private int succeededCount;
        private int failedCount;
        private int skippedItems;

        private PublishCounter() {
        }

        private PublishCounter(int attemptedCount, int succeededCount, int failedCount, int skippedItems) {
            this.attemptedCount = attemptedCount;
            this.succeededCount = succeededCount;
            this.failedCount = failedCount;
            this.skippedItems = skippedItems;
        }

        private void add(PublishCounter other) {
            attemptedCount += other.attemptedCount;
            succeededCount += other.succeededCount;
            failedCount += other.failedCount;
            skippedItems += other.skippedItems;
        }

        private int attemptedCount() {
            return attemptedCount;
        }

        private int succeededCount() {
            return succeededCount;
        }

        private int failedCount() {
            return failedCount;
        }

        private int skippedItems() {
            return skippedItems;
        }
    }

}
