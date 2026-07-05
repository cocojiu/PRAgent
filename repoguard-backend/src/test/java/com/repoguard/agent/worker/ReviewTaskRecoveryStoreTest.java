package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReviewTaskRecoveryStoreTest {

    private final ReviewTaskMapper reviewTaskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final ReviewTaskRecoveryStore store = new ReviewTaskRecoveryStore(
        reviewTaskMapper,
        new ReviewTaskStateMachine()
    );

    @Test
    void findsExpiredReviewingTasksWithLeaseFilterAndBatchLimit() {
        LocalDateTime expiredBefore = LocalDateTime.parse("2026-07-05T00:30:00");
        ReviewTask task = new ReviewTask();
        when(reviewTaskMapper.selectList(any())).thenReturn(List.of(task));

        List<ReviewTask> tasks = store.findExpiredReviewingTasks(expiredBefore, 25);

        assertThat(tasks).containsExactly(task);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<QueryWrapper<ReviewTask>> wrapperCaptor = ArgumentCaptor.forClass(QueryWrapper.class);
        verify(reviewTaskMapper).selectList(wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment())
            .contains("status")
            .contains("review_claimed_at")
            .contains("limit 25");
        assertThat(wrapperCaptor.getValue().getParamNameValuePairs().values())
            .contains("REVIEWING", expiredBefore);
    }

    @Test
    void requeuesTaskOnlyWhenClaimFenceMatches() {
        ReviewTask task = stuckTask();
        LocalDateTime recoveredAt = LocalDateTime.parse("2026-07-05T00:40:00");
        LocalDateTime expiredBefore = recoveredAt.minusMinutes(30);
        when(reviewTaskMapper.update(any(UpdateWrapper.class))).thenReturn(1);

        boolean requeued = store.requeueIfClaimOwned(task, recoveredAt, expiredBefore, "expired");

        assertThat(requeued).isTrue();
        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<ReviewTask>> wrapperCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
        verify(reviewTaskMapper).update(wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment())
            .contains("review_claimed_at")
            .contains("review_claimed_by");
        assertThat(wrapperCaptor.getValue().getSqlSet())
            .contains("next_publish_retry_at");
        assertThat(wrapperCaptor.getValue().getParamNameValuePairs().values())
            .contains("EXECUTION_TIMEOUT", recoveredAt, "expired");
    }

    @Test
    void returnsFalseWhenClaimFenceLost() {
        ReviewTask task = stuckTask();
        when(reviewTaskMapper.update(any(UpdateWrapper.class))).thenReturn(0);

        boolean requeued = store.requeueIfClaimOwned(
            task,
            LocalDateTime.parse("2026-07-05T00:40:00"),
            LocalDateTime.parse("2026-07-05T00:10:00"),
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
