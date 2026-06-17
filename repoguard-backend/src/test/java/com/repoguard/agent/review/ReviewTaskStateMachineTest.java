package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repoguard.agent.common.BusinessException;
import org.junit.jupiter.api.Test;

class ReviewTaskStateMachineTest {

    private final ReviewTaskStateMachine stateMachine = new ReviewTaskStateMachine();

    @Test
    void ensureRetryAllowedAcceptsOnlyFailedTasks() {
        stateMachine.ensureRetryAllowed("FAILED");

        assertThatThrownBy(() -> stateMachine.ensureRetryAllowed("COMPLETED"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Only failed review tasks can be retried");
    }

    @Test
    void canStartReviewAcceptsOnlyQueuedTasks() {
        assertThat(stateMachine.canStartReview("QUEUED")).isTrue();
        assertThat(stateMachine.canStartReview("REVIEWING")).isFalse();
        assertThat(stateMachine.canStartReview(null)).isFalse();
    }

    @Test
    void statusAfterReviewCompletedRespectsHumanReviewRequirement() {
        assertThat(stateMachine.statusWhenReviewing()).isEqualTo("REVIEWING");
        assertThat(stateMachine.statusAfterReviewCompleted(false)).isEqualTo("COMPLETED");
        assertThat(stateMachine.statusAfterReviewCompleted(true)).isEqualTo("PENDING_HUMAN_REVIEW");
    }

    @Test
    void statusAfterHumanReviewMapsDecisionsToTaskStatuses() {
        assertThat(stateMachine.statusAfterHumanReview("APPROVED")).isEqualTo("APPROVED");
        assertThat(stateMachine.statusAfterHumanReview("CHANGES_REQUESTED")).isEqualTo("CHANGES_REQUESTED");
        assertThat(stateMachine.statusAfterHumanReview("REJECTED")).isEqualTo("REJECTED");
        assertThat(stateMachine.statusAfterHumanReview("PENDING")).isEqualTo("PENDING_HUMAN_REVIEW");
    }
}
