package com.repoguard.agent.service.impl;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.GithubCommentPreviewItem;
import com.repoguard.agent.dto.GithubCommentPublishItem;
import com.repoguard.agent.dto.GithubCommentPublishResponse;
import com.repoguard.agent.entity.GithubCommentPublicationBatch;
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
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class GithubCommentPublishServiceImpl implements GithubCommentPublishService {

    private static final Logger LOGGER = LoggerFactory.getLogger(GithubCommentPublishServiceImpl.class);
    private static final int PUBLISH_CANDIDATE_BATCH_SIZE = 50;
    private static final long CLAIM_TIMEOUT_MS = 30 * 60 * 1000L;
    private static final long DISPATCH_RETRY_DELAY_MS = 60 * 1000L;

    private final ReviewTaskMapper reviewTaskMapper;
    private final GithubCommentPublishMetricsRecorder metricsRecorder;
    private final NotificationDispatchService notificationDispatchService;
    private final Executor publishExecutor;
    private final GithubCommentPublishCandidateLoader publishCandidateLoader;
    private final GithubCommentPublishGuard publishGuard;
    private final GithubCommentPublishPlanBuilder publishPlanBuilder;
    private final GithubCommentDraftPublisher draftPublisher;
    private final GithubCommentPublicationRecorder publicationRecorder;
    private final String workerId = "github-comment-publish-" + UUID.randomUUID();

    @Autowired
    public GithubCommentPublishServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        GithubCommentPublishMetricsRecorder metricsRecorder,
        NotificationDispatchService notificationDispatchService,
        GithubCommentPublishExecutor publishExecutor,
        GithubCommentPublishCandidateLoader publishCandidateLoader,
        GithubCommentPublishGuard publishGuard,
        GithubCommentPublishPlanBuilder publishPlanBuilder,
        GithubCommentDraftPublisher draftPublisher,
        GithubCommentPublicationRecorder publicationRecorder
    ) {
        this(
            reviewTaskMapper,
            metricsRecorder,
            notificationDispatchService,
            (Executor) publishExecutor,
            publishCandidateLoader,
            publishGuard,
            publishPlanBuilder,
            draftPublisher,
            publicationRecorder
        );
    }

    GithubCommentPublishServiceImpl(
        ReviewTaskMapper reviewTaskMapper,
        GithubCommentPublishMetricsRecorder metricsRecorder,
        NotificationDispatchService notificationDispatchService,
        Executor publishExecutor,
        GithubCommentPublishCandidateLoader publishCandidateLoader,
        GithubCommentPublishGuard publishGuard,
        GithubCommentPublishPlanBuilder publishPlanBuilder,
        GithubCommentDraftPublisher draftPublisher,
        GithubCommentPublicationRecorder publicationRecorder
    ) {
        this.reviewTaskMapper = Objects.requireNonNull(reviewTaskMapper, "reviewTaskMapper");
        this.metricsRecorder = Objects.requireNonNull(metricsRecorder, "metricsRecorder");
        this.notificationDispatchService = Objects.requireNonNull(notificationDispatchService, "notificationDispatchService");
        this.publishExecutor = Objects.requireNonNull(publishExecutor, "publishExecutor");
        this.publishCandidateLoader = Objects.requireNonNull(publishCandidateLoader, "publishCandidateLoader");
        this.publishGuard = Objects.requireNonNull(publishGuard, "publishGuard");
        this.publishPlanBuilder = Objects.requireNonNull(publishPlanBuilder, "publishPlanBuilder");
        this.draftPublisher = Objects.requireNonNull(draftPublisher, "draftPublisher");
        this.publicationRecorder = Objects.requireNonNull(publicationRecorder, "publicationRecorder");
    }

    @Override
    public GithubCommentPublishResponse publishGithubComments(Long taskId) {
        ReviewTask task = reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + taskId);
        }
        publishGuard.ensurePublishAllowed(task);

        GithubCommentPublishCandidateOverview overview = publishCandidateLoader.loadOverview(task);
        Long batchId = publicationRecorder.createBatch(task.getId(), overview.totalFindings());
        dispatchBatch(task.getId(), batchId, overview.totalFindings());
        return GithubCommentPublishResponse.queued(task.getId(), batchId, overview.totalFindings());
    }

    void dispatchRecoverableBatch(GithubCommentPublicationBatch batch) {
        if (batch == null || batch.getId() == null || batch.getTaskId() == null) {
            return;
        }
        dispatchBatch(batch.getTaskId(), batch.getId(), safe(batch.getTotalFindings()));
    }

    private void dispatchBatch(Long taskId, Long batchId, int totalFindings) {
        try {
            publishExecutor.execute(() -> publishGithubCommentsBatch(taskId, batchId, totalFindings));
        } catch (RejectedExecutionException ex) {
            LocalDateTime retryAt = LocalDateTime.now().plusNanos(DISPATCH_RETRY_DELAY_MS * 1_000_000);
            String error = "GitHub comment publish dispatch rejected: " + errorSummary(ex);
            publicationRecorder.markBatchQueuedForRetry(batchId, retryAt, error);
            LOGGER.warn(
                "GitHub comment publish dispatch rejected. taskId={}, batchId={}, nextRetryAt={}",
                taskId,
                batchId,
                retryAt,
                ex
            );
        }
    }

    private void publishGithubCommentsBatch(Long taskId, Long batchId, int fallbackTotalFindings) {
        LocalDateTime startedAt = LocalDateTime.now();
        int totalFindings = fallbackTotalFindings;
        try {
            if (!publicationRecorder.tryMarkBatchRunning(
                batchId,
                workerId,
                startedAt,
                startedAt.minusNanos(CLAIM_TIMEOUT_MS * 1_000_000)
            )) {
                LOGGER.info("GitHub comment publish batch skipped because claim was not acquired. taskId={}, batchId={}", taskId, batchId);
                return;
            }
            ReviewTask task = reviewTaskMapper.selectById(taskId);
            if (task == null) {
                publicationRecorder.failBatch(batchId, totalFindings, "Review task not found: " + taskId);
                return;
            }
            publishGuard.ensurePublishAllowed(task);
            GithubCommentPublishCandidateOverview overview = publishCandidateLoader.loadOverview(task);
            totalFindings = overview.totalFindings();
            GithubCommentPublishResponse response = executeGithubCommentPublishBatch(task, batchId, overview);
            publicationRecorder.completeBatch(batchId, response);
            publishGithubCommentNotification(task, response, batchId);
            metricsRecorder.recordDuration(startedAt, response.failedCount() != null && response.failedCount() > 0);
        } catch (RuntimeException ex) {
            LOGGER.warn("GitHub comment publish batch failed. taskId={}, batchId={}", taskId, batchId, ex);
            publicationRecorder.failBatch(batchId, totalFindings, errorSummary(ex));
            metricsRecorder.recordDuration(startedAt, true);
        }
    }

    private GithubCommentPublishResponse executeGithubCommentPublishBatch(
        ReviewTask task,
        Long batchId,
        GithubCommentPublishCandidateOverview overview
    ) {
        List<GithubCommentPublishItem> items = new ArrayList<>();
        PublishCounter counter = publishCandidatesInBatches(task, overview, items);
        int skippedCount = Math.max(counter.skippedItems(), totalPublishCandidates(overview) - counter.attemptedCount());
        metricsRecorder.recordItems(counter.succeededCount(), counter.failedCount(), skippedCount);
        return new GithubCommentPublishResponse(
            task.getId(),
            batchId,
            null,
            overview.totalFindings(),
            counter.attemptedCount(),
            counter.succeededCount(),
            counter.failedCount(),
            skippedCount,
            items
        );
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

    private int safe(Integer value) {
        return value == null ? 0 : value;
    }

    private String errorSummary(Throwable throwable) {
        if (throwable == null) {
            return "unknown error";
        }
        String message = throwable.getMessage();
        if (message == null || message.isBlank()) {
            return throwable.getClass().getSimpleName();
        }
        return throwable.getClass().getSimpleName() + ": " + message;
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
