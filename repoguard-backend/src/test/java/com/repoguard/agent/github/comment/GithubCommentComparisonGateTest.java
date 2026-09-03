package com.repoguard.agent.github.comment;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.review.execution.ReviewFindingComparisonService;
import org.junit.jupiter.api.Test;

class GithubCommentComparisonGateTest {

    private final ReviewFindingComparisonService comparisonService =
        org.mockito.Mockito.mock(ReviewFindingComparisonService.class);

    @Test
    void comparesCurrentAttemptBeforeWriteback() {
        ReviewTask task = new ReviewTask();
        task.setId(42L);
        task.setCurrentAttemptId(7L);

        new GithubCommentComparisonGate(comparisonService).ensureCompared(task);

        verify(comparisonService).compare(42L, null, 7L, 1, 1);
    }

    @Test
    void rejectsTaskWithoutCurrentAttempt() {
        ReviewTask task = new ReviewTask();
        task.setId(42L);

        assertThatThrownBy(() -> new GithubCommentComparisonGate(comparisonService).ensureCompared(task))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("current review execution attempt");
    }

    @Test
    void disabledCompatibilityGateDoesNotRequireComparisonService() {
        GithubCommentComparisonGate.disabled().ensureCompared(null);
    }
}
