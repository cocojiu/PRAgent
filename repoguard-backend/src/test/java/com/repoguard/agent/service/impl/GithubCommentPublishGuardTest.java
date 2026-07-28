package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import org.junit.jupiter.api.Test;

class GithubCommentPublishGuardTest {

    private final GithubCommentPublishGuard guard = new GithubCommentPublishGuard(new ReviewTaskStateMachine());

    @Test
    void constructorRejectsMissingStateMachine() {
        assertThatThrownBy(() -> new GithubCommentPublishGuard(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("reviewTaskStateMachine");
    }

    @Test
    void ensurePublishAllowedAllowsTaskWithoutHumanReviewRequirement() {
        ReviewTask task = new ReviewTask();
        task.setHumanReviewRequired(false);

        guard.ensurePublishAllowed(task);
    }

    @Test
    void ensurePublishAllowedAllowsApprovedHumanReviewTask() {
        ReviewTask task = humanReviewTask("approved");

        guard.ensurePublishAllowed(task);
    }

    @Test
    void ensurePublishAllowedAllowsChangesRequestedHumanReviewTask() {
        ReviewTask task = humanReviewTask("CHANGES_REQUESTED");

        guard.ensurePublishAllowed(task);
    }

    @Test
    void ensurePublishAllowedRejectsPendingHumanReviewTask() {
        ReviewTask task = humanReviewTask("PENDING");

        assertThatThrownBy(() -> guard.ensurePublishAllowed(task))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Human review approval");
    }

    @Test
    void ensurePublishAllowedRejectsSupersededTask() {
        ReviewTask task = new ReviewTask();
        task.setStatus("SUPERSEDED");
        task.setHumanReviewRequired(false);

        assertThatThrownBy(() -> guard.ensurePublishAllowed(task))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("Superseded review tasks");
    }

    @Test
    void resolveHumanReviewStatusFallsBackToPendingWhenRequiredStatusMissing() {
        ReviewTask task = new ReviewTask();
        task.setHumanReviewRequired(true);

        assertThat(guard.resolveHumanReviewStatus(task)).isEqualTo("PENDING");
    }

    @Test
    void resolveHumanReviewStatusFallsBackToNotRequiredWhenHumanReviewIsNotRequired() {
        ReviewTask task = new ReviewTask();
        task.setHumanReviewRequired(false);

        assertThat(guard.resolveHumanReviewStatus(task)).isEqualTo("NOT_REQUIRED");
    }

    private ReviewTask humanReviewTask(String status) {
        ReviewTask task = new ReviewTask();
        task.setHumanReviewRequired(true);
        task.setHumanReviewStatus(status);
        return task;
    }
}
