package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.messaging.ReviewTaskPublishOutboxStore;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReviewTaskRecoveryStoreTest {

    private final ReviewTaskMapper reviewTaskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final ReviewTaskClaimService claimService = org.mockito.Mockito.mock(ReviewTaskClaimService.class);
    private final ReviewTaskPublishOutboxStore outboxStore =
        org.mockito.Mockito.mock(ReviewTaskPublishOutboxStore.class);
    private final ReviewTaskRecoveryStore store = new ReviewTaskRecoveryStore(
        reviewTaskMapper,
        new ReviewTaskStateMachine(),
        claimService,
        outboxStore
    );

    @Test
    void findsExpiredReviewingTasksWithLeaseFilterAndBatchLimit() {
        LocalDateTime expiredBefore = LocalDateTime.parse("2026-07-05T00:30:00");
        ReviewTask task = new ReviewTask();
        when(reviewTaskMapper.selectList(any())).thenReturn(List.of(task));

        List<ReviewTask> tasks = store.findExpiredReviewingTasks(expiredBefore, 25);

        assertThat(tasks).containsExactly(task);
        ArgumentCaptor<QueryWrapper<ReviewTask>> wrapperCaptor = ArgumentCaptor.captor();
        verify(reviewTaskMapper).selectList(wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment())
            .contains("status")
            .contains("review_claimed_at")
            .contains("limit 25");
        assertThat(wrapperCaptor.getValue().getParamNameValuePairs().values())
            .contains("REVIEWING", expiredBefore);
    }

    @Test
    void marksRequeuePendingOnlyWhenClaimFenceMatches() {
        ReviewTask task = stuckTask();
        LocalDateTime recoveredAt = LocalDateTime.parse("2026-07-05T00:40:00");
        LocalDateTime expiredBefore = recoveredAt.minusMinutes(30);
        when(claimService.markRequeuePendingIfClaimOwned(task, recoveredAt, expiredBefore, "expired"))
            .thenReturn(true);

        boolean requeued = store.markRequeuePendingIfClaimOwned(task, recoveredAt, expiredBefore, "expired");

        assertThat(requeued).isTrue();
        verify(claimService).markRequeuePendingIfClaimOwned(task, recoveredAt, expiredBefore, "expired");
    }

    @Test
    void marksQueuedForRecoveryPublishFromRequeuePending() {
        ReviewTask task = stuckTask();
        task.setStatus("REQUEUE_PENDING");
        when(outboxStore.markQueuedForRecoveryPublish(task, 1)).thenReturn(true);

        boolean queued = store.markQueuedForRecoveryPublish(task, LocalDateTime.parse("2026-07-05T00:40:00"), 1);

        assertThat(queued).isTrue();
        verify(outboxStore).markQueuedForRecoveryPublish(task, 1);
    }

    @Test
    void marksRecoveryPublishFailureAsPublishFailed() {
        ReviewTask task = stuckTask();
        task.setStatus("QUEUED");
        LocalDateTime nextRetryAt = LocalDateTime.parse("2026-07-05T00:41:00");
        when(outboxStore.markRecoveryPublishFailed(task, nextRetryAt, "publisher confirm timed out"))
            .thenReturn(true);

        boolean failed = store.markRecoveryPublishFailed(
            task,
            LocalDateTime.parse("2026-07-05T00:40:00"),
            nextRetryAt,
            "publisher confirm timed out"
        );

        assertThat(failed).isTrue();
        verify(outboxStore).markRecoveryPublishFailed(task, nextRetryAt, "publisher confirm timed out");
    }

    @Test
    void returnsFalseWhenClaimFenceLost() {
        ReviewTask task = stuckTask();
        LocalDateTime recoveredAt = LocalDateTime.parse("2026-07-05T00:40:00");
        LocalDateTime expiredBefore = LocalDateTime.parse("2026-07-05T00:10:00");
        when(claimService.markRequeuePendingIfClaimOwned(task, recoveredAt, expiredBefore, "expired"))
            .thenReturn(false);

        boolean requeued = store.markRequeuePendingIfClaimOwned(
            task,
            recoveredAt,
            expiredBefore,
            "expired"
        );

        assertThat(requeued).isFalse();
    }

    private ReviewTask stuckTask() {
        ReviewTask task = new ReviewTask();
        task.setId(42L);
        task.setReviewClaimedAt(LocalDateTime.parse("2026-07-05T00:00:00"));
        task.setReviewClaimedBy("old-execution-token");
        return task;
    }
}
