package com.repoguard.agent.review.task;

import com.repoguard.agent.entity.ReviewTask;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class ReviewTaskAfterCommitPublisher {

    private final ReviewTaskPublisher reviewTaskPublisher;
    private final ReviewTaskPublishFailureStore publishFailureStore;
    private final Executor reviewPublishExecutor;

    @Autowired
    public ReviewTaskAfterCommitPublisher(
        ReviewTaskPublisher reviewTaskPublisher,
        ReviewTaskPublishFailureStore publishFailureStore,
        ReviewTaskAfterCommitPublisherExecutor reviewPublishExecutor
    ) {
        this(
            reviewTaskPublisher,
            publishFailureStore,
            (Executor) reviewPublishExecutor
        );
    }

    ReviewTaskAfterCommitPublisher(
        ReviewTaskPublisher reviewTaskPublisher,
        ReviewTaskPublishFailureStore publishFailureStore,
        Executor reviewPublishExecutor
    ) {
        this.reviewTaskPublisher = Objects.requireNonNull(reviewTaskPublisher, "reviewTaskPublisher");
        this.publishFailureStore = Objects.requireNonNull(publishFailureStore, "outboxStore");
        this.reviewPublishExecutor = Objects.requireNonNull(reviewPublishExecutor, "reviewPublishExecutor");
    }

    public boolean publishAfterCommit(ReviewTask task, ReviewTaskMessage message, LocalDateTime queuedAt) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return publishAndMarkFailure(task, message, queuedAt);
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                executeAfterCommit(task, message, queuedAt);
            }
        });
        return true;
    }

    private void executeAfterCommit(ReviewTask task, ReviewTaskMessage message, LocalDateTime queuedAt) {
        try {
            reviewPublishExecutor.execute(() -> publishAndMarkFailure(task, message, queuedAt));
        } catch (RejectedExecutionException ex) {
            markPublishFailed(task, publishExecutorRejected(ex), queuedAt);
        }
    }

    private ReviewTaskPublishException publishExecutorRejected(RejectedExecutionException ex) {
        String detail = ex.getMessage() == null || ex.getMessage().isBlank()
            ? ex.getClass().getSimpleName()
            : ex.getMessage();
        return new ReviewTaskPublishException("Review publish executor rejected task: " + detail, ex);
    }

    private boolean publishAndMarkFailure(ReviewTask task, ReviewTaskMessage message, LocalDateTime queuedAt) {
        try {
            reviewTaskPublisher.publish(message);
            return true;
        } catch (ReviewTaskPublishException ex) {
            markPublishFailed(task, ex, queuedAt);
            return false;
        }
    }

    public void markPublishFailed(ReviewTask task, ReviewTaskPublishException ex, LocalDateTime failedAt) {
        publishFailureStore.markDirectPublishFailed(
            task,
            ex,
            failedAt,
            ReviewTaskDirectPublishFailurePolicy.directPublish(60000)
        );
    }
}
