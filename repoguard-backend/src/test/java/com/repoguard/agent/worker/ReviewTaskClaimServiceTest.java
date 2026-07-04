package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ReviewTaskClaimServiceTest {

    private final ReviewTaskClaimService claimService = new ReviewTaskClaimService(
        org.mockito.Mockito.mock(ReviewTaskMapper.class),
        new ReviewTaskStateMachine()
    );

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
}
