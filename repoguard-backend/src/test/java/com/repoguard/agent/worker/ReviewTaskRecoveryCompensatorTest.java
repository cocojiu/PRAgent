package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.config.RabbitReviewQueueProperties;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.observability.LogContext;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.slf4j.MDC;

class ReviewTaskRecoveryCompensatorTest {

    private final ReviewTaskMapper reviewTaskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final ReviewTimelineMapper reviewTimelineMapper = org.mockito.Mockito.mock(ReviewTimelineMapper.class);
    private final ReviewTaskRecoveryCompensator compensator = new ReviewTaskRecoveryCompensator(
        reviewTaskMapper,
        new ReviewTimelineAppender(reviewTimelineMapper),
        new RabbitReviewQueueProperties(),
        new ReviewTaskStateMachine(),
        new ReviewExecutionClock(),
        new ReviewLogContextFormatter()
    );

    @Test
    void recoverMovesOwnedStuckTaskIntoExistingPublishCompensationFlow() {
        ReviewTask task = stuckTask();
        LocalDateTime recoveredAt = LocalDateTime.parse("2026-06-20T12:00:00");
        LocalDateTime expiredBefore = recoveredAt.minusMinutes(30);
        when(reviewTaskMapper.update(any(UpdateWrapper.class))).thenReturn(1);

        compensator.recover(task, recoveredAt, expiredBefore);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<ReviewTask>> wrapperCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(reviewTaskMapper).update(wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment())
            .contains("review_claimed_at")
            .contains("review_claimed_by");
        assertThat(wrapperCaptor.getValue().getSqlSet())
            .contains("next_publish_retry_at");
        assertThat(wrapperCaptor.getValue().getParamNameValuePairs().values())
            .contains("PUBLISH_FAILED", recoveredAt, "Review execution lease expired");

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
        when(reviewTaskMapper.update(any(UpdateWrapper.class))).thenReturn(0);

        compensator.recover(task, recoveredAt, recoveredAt.minusMinutes(30));

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
