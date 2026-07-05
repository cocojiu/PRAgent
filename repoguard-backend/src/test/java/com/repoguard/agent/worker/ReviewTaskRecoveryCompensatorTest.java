package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.config.RabbitReviewQueueProperties;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.messaging.MessagePublishException;
import com.repoguard.agent.messaging.ReviewTaskMessage;
import com.repoguard.agent.messaging.ReviewTaskPublisher;
import com.repoguard.agent.observability.LogContext;
import com.repoguard.agent.observability.RepoGuardMetrics;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.slf4j.MDC;

class ReviewTaskRecoveryCompensatorTest {

    private final ReviewTaskRecoveryStore recoveryStore = org.mockito.Mockito.mock(ReviewTaskRecoveryStore.class);
    private final ReviewTaskRecoveryTimelineRecorder timelineRecorder =
        org.mockito.Mockito.mock(ReviewTaskRecoveryTimelineRecorder.class);
    private final ReviewTaskPublisher reviewTaskPublisher = org.mockito.Mockito.mock(ReviewTaskPublisher.class);
    private final RepoGuardMetrics metrics = org.mockito.Mockito.mock(RepoGuardMetrics.class);
    private final ReviewTaskRecoveryCompensator compensator = new ReviewTaskRecoveryCompensator(
        recoveryStore,
        timelineRecorder,
        new ReviewExecutionClock(),
        new ReviewLogContextFormatter(),
        new ReviewTaskRecoveryPolicy(new RabbitReviewQueueProperties()),
        reviewTaskPublisher,
        metrics
    );

    @Test
    void recoverDirectlyPublishesOwnedStuckTask() {
        ReviewTask task = stuckTask();
        LocalDateTime recoveredAt = LocalDateTime.parse("2026-06-20T12:00:00");
        LocalDateTime expiredBefore = recoveredAt.minusMinutes(30);
        when(recoveryStore.markRequeuePendingIfClaimOwned(task, recoveredAt, expiredBefore, "Review execution lease expired"))
            .thenReturn(true);
        when(recoveryStore.markQueuedForRecoveryPublish(task, recoveredAt, 1))
            .thenReturn(true);

        compensator.recover(task, recoveredAt, expiredBefore);

        InOrder ordered = inOrder(recoveryStore, timelineRecorder, reviewTaskPublisher);
        ordered.verify(recoveryStore).markRequeuePendingIfClaimOwned(task, recoveredAt, expiredBefore, "Review execution lease expired");
        ordered.verify(timelineRecorder).requeuePending(task, recoveredAt);
        ordered.verify(recoveryStore).markQueuedForRecoveryPublish(task, recoveredAt, 1);
        ordered.verify(reviewTaskPublisher).publish(any(ReviewTaskMessage.class));
        ordered.verify(timelineRecorder).recoveryQueued(task, recoveredAt);
        assertLogContextCleared();
    }

    @Test
    void recoverMarksPublishFailedWhenDirectRecoveryPublishFails() {
        ReviewTask task = stuckTask();
        LocalDateTime recoveredAt = LocalDateTime.parse("2026-06-20T12:00:00");
        LocalDateTime expiredBefore = recoveredAt.minusMinutes(30);
        when(recoveryStore.markRequeuePendingIfClaimOwned(task, recoveredAt, expiredBefore, "Review execution lease expired"))
            .thenReturn(true);
        when(recoveryStore.markQueuedForRecoveryPublish(task, recoveredAt, 1))
            .thenReturn(true);
        doThrow(new MessagePublishException("publisher confirm timed out password=raw-password"))
            .when(reviewTaskPublisher)
            .publish(any(ReviewTaskMessage.class));
        when(recoveryStore.markRecoveryPublishFailed(
            any(ReviewTask.class),
            any(LocalDateTime.class),
            any(LocalDateTime.class),
            any(String.class)
        )).thenReturn(true);

        compensator.recover(task, recoveredAt, expiredBefore);

        ArgumentCaptor<String> errorCaptor = ArgumentCaptor.forClass(String.class);
        verify(recoveryStore).markRecoveryPublishFailed(
            any(ReviewTask.class),
            any(LocalDateTime.class),
            any(LocalDateTime.class),
            errorCaptor.capture()
        );
        assertThat(errorCaptor.getValue()).contains("password=****").doesNotContain("raw-password");
        verify(timelineRecorder).recoveryPublishFailed(task, recoveredAt, errorCaptor.getValue());
        verify(metrics).rabbitPublishFailed("execute", errorCaptor.getValue());
        assertLogContextCleared();
    }

    @Test
    void recoverDoesNothingWhenAnotherRecoveryAlreadyWonClaim() {
        ReviewTask task = stuckTask();
        LocalDateTime recoveredAt = LocalDateTime.parse("2026-06-20T12:00:00");
        LocalDateTime expiredBefore = recoveredAt.minusMinutes(30);
        when(recoveryStore.markRequeuePendingIfClaimOwned(task, recoveredAt, expiredBefore, "Review execution lease expired"))
            .thenReturn(false);

        compensator.recover(task, recoveredAt, expiredBefore);

        verify(reviewTaskPublisher, never()).publish(any(ReviewTaskMessage.class));
        verify(timelineRecorder, never()).requeuePending(any(ReviewTask.class), any(LocalDateTime.class));
        verify(timelineRecorder, never()).recoveryQueued(any(ReviewTask.class), any(LocalDateTime.class));
        assertLogContextCleared();
    }

    private ReviewTask stuckTask() {
        ReviewTask task = new ReviewTask();
        task.setId(42L);
        task.setOrganization("octocat");
        task.setRepository("Hello-World");
        task.setPrNumber(1);
        task.setStatus("REVIEWING");
        task.setReviewClaimedAt(LocalDateTime.parse("2026-06-20T10:00:00"));
        task.setReviewClaimedBy("old-execution-token");
        return task;
    }

    private void assertLogContextCleared() {
        assertThat(MDC.get(LogContext.TASK_ID)).isNull();
        assertThat(MDC.get(LogContext.REPOSITORY)).isNull();
        assertThat(MDC.get(LogContext.PR_NUMBER)).isNull();
    }
}
