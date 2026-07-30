package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ReviewTaskClaimServiceTest {

    private final ReviewTaskMapper reviewTaskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final ReviewTaskClaimService claimService =
        new ReviewTaskClaimService(reviewTaskMapper, new ReviewTaskStateMachine());

    @Test
    void releasesReviewClaimFromTaskSnapshot() {
        ReviewTask task = new ReviewTask();
        task.setReviewClaimedAt(LocalDateTime.parse("2026-07-05T01:00:00"));
        task.setReviewClaimedBy("execution-claim");

        claimService.releaseReviewClaim(task);

        assertThat(task.getReviewClaimedAt()).isNull();
        assertThat(task.getReviewClaimedBy()).isNull();
    }

    @Test
    void releaseRequiresTask() {
        assertThatThrownBy(() -> claimService.releaseReviewClaim(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("task");
    }

    @Test
    void terminalFencePersistsBusinessFieldsAndReleasesClaimInOneUpdate() {
        ReviewTask task = new ReviewTask();
        task.setId(42L);
        task.setStatus("COMPLETED");
        task.setRiskLevel("LOW");
        task.setLlmStatus("COMPLETED");
        task.setLlmProvider(null);
        task.setHumanReviewRequired(false);
        task.setHumanReviewStatus("NOT_REQUIRED");
        task.setFinishedAt(LocalDateTime.parse("2026-07-28T10:05:00"));
        task.setDurationSeconds(300);
        task.setReviewClaimedAt(LocalDateTime.parse("2026-07-28T10:00:00"));
        task.setReviewClaimedBy("claim-1");
        when(reviewTaskMapper.update(any())).thenReturn(1);

        boolean written = claimService.writeTerminalStateIfClaimOwned(task, "claim-1");

        assertThat(written).isTrue();
        assertThat(task.getReviewClaimedAt()).isNull();
        assertThat(task.getReviewClaimedBy()).isNull();
        ArgumentCaptor<UpdateWrapper<ReviewTask>> wrapperCaptor = ArgumentCaptor.captor();
        verify(reviewTaskMapper).update(wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSegment())
            .contains("status", "review_claimed_by");
        assertThat(wrapperCaptor.getValue().getSqlSet())
            .contains("llm_provider", "human_review_note", "finished_at", "review_claimed_at");
    }

    @Test
    void recoveryFenceMovesOnlyOwnedExpiredReviewToPending() {
        ReviewTask task = new ReviewTask();
        task.setId(42L);
        task.setStatus("REVIEWING");
        task.setReviewClaimedAt(LocalDateTime.parse("2026-07-28T09:00:00"));
        task.setReviewClaimedBy("claim-1");
        LocalDateTime expiredBefore = LocalDateTime.parse("2026-07-28T09:30:00");
        when(reviewTaskMapper.update(any())).thenReturn(1);

        boolean written = claimService.markRequeuePendingIfClaimOwned(task, expiredBefore, "expired");

        assertThat(written).isTrue();
        assertThat(task.getStatus()).isEqualTo("REQUEUE_PENDING");
        assertThat(task.getReviewClaimedAt()).isNull();
        assertThat(task.getReviewClaimedBy()).isNull();
    }
}
