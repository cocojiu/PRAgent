package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.messaging.ReviewTaskMessage;
import com.repoguard.agent.messaging.ReviewTaskPublishOutboxStore;
import com.repoguard.agent.messaging.ReviewTaskPublisher;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import com.repoguard.agent.timeline.ReviewTimelineAppender;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class ReviewTaskAfterCommitPublisherTest {

    private final ReviewTaskMapper reviewTaskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final ReviewTimelineMapper reviewTimelineMapper = org.mockito.Mockito.mock(ReviewTimelineMapper.class);
    private final ReviewTaskPublisher reviewTaskPublisher = org.mockito.Mockito.mock(ReviewTaskPublisher.class);

    @AfterEach
    void clearTransactionSynchronization() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void constructorRejectsMissingOutboxStore() {
        assertThatThrownBy(() -> new ReviewTaskAfterCommitPublisher(
            reviewTaskPublisher,
            null,
            Runnable::run
        ))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("outboxStore");
    }

    @Test
    void marksPublishFailedWhenExecutorRejectsAfterTransactionCommit() {
        ReviewTask task = task();
        LocalDateTime queuedAt = LocalDateTime.of(2026, 7, 4, 9, 30);
        ReviewTimeline latest = new ReviewTimeline();
        latest.setSortOrder(3);
        when(reviewTimelineMapper.selectOne(any())).thenReturn(latest);
        ReviewTaskAfterCommitPublisher publisher = new ReviewTaskAfterCommitPublisher(
            reviewTaskPublisher,
            new ReviewTaskPublishOutboxStore(
                reviewTaskMapper,
                new ReviewTimelineAppender(reviewTimelineMapper),
                new ReviewTaskStateMachine()
            ),
            command -> {
                throw new RejectedExecutionException(
                    "executor shutting down token=raw-token password=raw-password"
                );
            }
        );

        TransactionSynchronizationManager.initSynchronization();

        boolean accepted = publisher.publishAfterCommit(task, message(queuedAt), queuedAt);
        List<TransactionSynchronization> synchronizations = TransactionSynchronizationManager.getSynchronizations();
        synchronizations.getFirst().afterCommit();

        assertThat(accepted).isTrue();
        verify(reviewTaskPublisher, never()).publish(any(ReviewTaskMessage.class));

        ArgumentCaptor<ReviewTask> taskCaptor = ArgumentCaptor.forClass(ReviewTask.class);
        verify(reviewTaskMapper).updateById(taskCaptor.capture());
        ReviewTask failedTask = taskCaptor.getValue();
        assertThat(failedTask.getStatus()).isEqualTo("PUBLISH_FAILED");
        assertThat(failedTask.getLlmStatus()).isEqualTo("PENDING");
        assertThat(failedTask.getPublishAttempts()).isEqualTo(1);
        assertThat(failedTask.getNextPublishRetryAt()).isEqualTo(queuedAt.plusSeconds(60));
        assertThat(failedTask.getLastPublishError())
            .contains("Review publish executor rejected task")
            .contains("token=****", "password=****")
            .doesNotContain("raw-token", "raw-password");

        ArgumentCaptor<ReviewTimeline> timelineCaptor = ArgumentCaptor.forClass(ReviewTimeline.class);
        verify(reviewTimelineMapper).insert(timelineCaptor.capture());
        ReviewTimeline timeline = timelineCaptor.getValue();
        assertThat(timeline.getTaskId()).isEqualTo(522L);
        assertThat(timeline.getStatus()).isEqualTo("FAILED");
        assertThat(timeline.getSortOrder()).isEqualTo(4);
        assertThat(timeline.getLabel())
            .contains("Message publish failed")
            .contains("token=****", "password=****")
            .doesNotContain("raw-token", "raw-password");
    }

    private ReviewTask task() {
        ReviewTask task = new ReviewTask();
        task.setId(522L);
        task.setStatus("QUEUED");
        task.setLlmStatus("PENDING");
        task.setPublishAttempts(0);
        task.setLlmProvider("openai");
        task.setLlmModel("gpt-4.1");
        task.setLlmDurationMs(1200);
        task.setLlmParseStatus("OK");
        task.setLlmFallbackReason("none");
        task.setLlmPromptSummary("summary");
        return task;
    }

    private ReviewTaskMessage message(LocalDateTime queuedAt) {
        return new ReviewTaskMessage(
            522L,
            "octocat",
            "Hello-World",
            2,
            "public-pr-after-commit",
            queuedAt
        );
    }
}
