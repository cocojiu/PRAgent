package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.config.RabbitReviewQueueProperties;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.observability.LogContext;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

class ReviewTaskRecoveryCompensatorTest {

    private final ReviewTaskRecoveryStore recoveryStore = org.mockito.Mockito.mock(ReviewTaskRecoveryStore.class);
    private final ReviewTimelineMapper reviewTimelineMapper = org.mockito.Mockito.mock(ReviewTimelineMapper.class);
    private final ReviewTaskRecoveryCompensator compensator = new ReviewTaskRecoveryCompensator(
        recoveryStore,
        new ReviewTimelineAppender(reviewTimelineMapper),
        new ReviewExecutionClock(),
        new ReviewLogContextFormatter(),
        new ReviewTaskRecoveryPolicy(new RabbitReviewQueueProperties())
    );

    @Test
    void recoverMovesOwnedStuckTaskIntoExistingPublishCompensationFlow() {
        ReviewTask task = stuckTask();
        LocalDateTime recoveredAt = LocalDateTime.parse("2026-06-20T12:00:00");
        LocalDateTime expiredBefore = recoveredAt.minusMinutes(30);
        when(recoveryStore.requeueIfClaimOwned(task, recoveredAt, expiredBefore, "Review execution lease expired"))
            .thenReturn(true);

        compensator.recover(task, recoveredAt, expiredBefore);

        ArgumentCaptor<ReviewTimeline> timelineCaptor = ArgumentCaptor.forClass(ReviewTimeline.class);
        verify(reviewTimelineMapper).insert(timelineCaptor.capture());
        assertThat(timelineCaptor.getValue().getLabel()).contains("queued for recovery");
        assertThat(timelineCaptor.getValue().getStatus()).isEqualTo("CURRENT");
        assertLogContextCleared();
    }

    @Test
    void recoverDoesNothingWhenAnotherRecoveryAlreadyWonClaim() {
        ReviewTask task = stuckTask();
        LocalDateTime recoveredAt = LocalDateTime.parse("2026-06-20T12:00:00");
        LocalDateTime expiredBefore = recoveredAt.minusMinutes(30);
        when(recoveryStore.requeueIfClaimOwned(task, recoveredAt, expiredBefore, "Review execution lease expired"))
            .thenReturn(false);

        compensator.recover(task, recoveredAt, expiredBefore);

        verify(reviewTimelineMapper, never()).insert(any(ReviewTimeline.class));
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
