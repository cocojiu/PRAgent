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
        assertThat(stateMachine.statusWhenQueued()).isEqualTo("QUEUED");
        assertThat(stateMachine.statusWhenPublishFailed()).isEqualTo("PUBLISH_FAILED");
        assertThat(stateMachine.statusWhenFailed()).isEqualTo("FAILED");
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

    @Test
    void canPublishGithubCommentsRequiresFinalHumanReviewDecisionWhenNeeded() {
        assertThat(stateMachine.canPublishGithubComments(false, "PENDING")).isTrue();
        assertThat(stateMachine.canPublishGithubComments(true, "APPROVED")).isTrue();
        assertThat(stateMachine.canPublishGithubComments(true, "CHANGES_REQUESTED")).isTrue();
        assertThat(stateMachine.canPublishGithubComments(true, "PENDING")).isFalse();
        assertThat(stateMachine.canPublishGithubComments(true, "REJECTED")).isFalse();
    }

    @Test
    void ensureHumanReviewAllowedRequiresPendingRequiredReview() {
        stateMachine.ensureHumanReviewAllowed(true, "PENDING");

        assertThatThrownBy(() -> stateMachine.ensureHumanReviewAllowed(false, "NOT_REQUIRED"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Human review is not required");
        assertThatThrownBy(() -> stateMachine.ensureHumanReviewAllowed(true, "APPROVED"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("already been decided");
    }

    @Test
    void ensurePublishRequeueAllowedRequiresUnclaimedPublishFailedTask() {
        stateMachine.ensurePublishRequeueAllowed("PUBLISH_FAILED", false);

        assertThat(stateMachine.isPublishFailed("PUBLISH_FAILED")).isTrue();
        assertThat(stateMachine.isPublishFailed("QUEUED")).isFalse();
        assertThatThrownBy(() -> stateMachine.ensurePublishRequeueAllowed("FAILED", false))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Only publish failed message tasks can be requeued");
        assertThatThrownBy(() -> stateMachine.ensurePublishRequeueAllowed("PUBLISH_FAILED", true))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Claimed message tasks cannot be requeued");
    }

    @Test
    void dataRetentionCandidateStatusesIncludeOnlyTerminalCleanupStatuses() {
        assertThat(stateMachine.dataRetentionCandidateStatuses()).containsExactly("COMPLETED", "FAILED");
    }
}
