package com.repoguard.agent.service.impl;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.messaging.MessagePublishException;
import com.repoguard.agent.messaging.ReviewTaskMessage;
import com.repoguard.agent.messaging.ReviewTaskPublishOutboxStore;
import com.repoguard.agent.messaging.ReviewTaskPublisher;
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
    private final ReviewTaskPublishOutboxStore outboxStore;
    private final Executor reviewPublishExecutor;

    @Autowired
    public ReviewTaskAfterCommitPublisher(
        ReviewTaskPublisher reviewTaskPublisher,
        ReviewTaskPublishOutboxStore outboxStore,
        ReviewTaskAfterCommitPublisherExecutor reviewPublishExecutor
    ) {
        this(
            reviewTaskPublisher,
            outboxStore,
            (Executor) reviewPublishExecutor
        );
    }

    ReviewTaskAfterCommitPublisher(
        ReviewTaskPublisher reviewTaskPublisher,
        ReviewTaskPublishOutboxStore outboxStore,
        Executor reviewPublishExecutor
    ) {
        this.reviewTaskPublisher = reviewTaskPublisher;
        this.outboxStore = Objects.requireNonNull(outboxStore, "outboxStore");
        this.reviewPublishExecutor = reviewPublishExecutor == null ? Runnable::run : reviewPublishExecutor;
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

    private MessagePublishException publishExecutorRejected(RejectedExecutionException ex) {
        String detail = ex.getMessage() == null || ex.getMessage().isBlank()
            ? ex.getClass().getSimpleName()
            : ex.getMessage();
        return new MessagePublishException("Review publish executor rejected task: " + detail, ex);
    }

    private boolean publishAndMarkFailure(ReviewTask task, ReviewTaskMessage message, LocalDateTime queuedAt) {
        try {
            reviewTaskPublisher.publish(message);
            return true;
        } catch (MessagePublishException ex) {
            markPublishFailed(task, ex, queuedAt);
            return false;
        }
    }

    public void markPublishFailed(ReviewTask task, MessagePublishException ex, LocalDateTime failedAt) {
        outboxStore.markDirectPublishFailed(
            task,
            ex,
            failedAt,
            60000,
            "Message publish failed: ",
            true,
            false
        );
    }
}
