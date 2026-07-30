package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.ReviewResult;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import com.repoguard.agent.review.RiskLevelRanker;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewExecutionTaskTerminalWriterTest {

    private final ReviewTaskMapper reviewTaskMapper = org.mockito.Mockito.mock(ReviewTaskMapper.class);
    private final ReviewTaskStateMachine stateMachine = new ReviewTaskStateMachine();
    private final ReviewExecutionTaskTerminalWriter writer = new ReviewExecutionTaskTerminalWriter(
        new ReviewTaskClaimService(reviewTaskMapper, stateMachine),
        new ReviewTaskCompletionApplier(
            stateMachine,
            new ReviewHumanReviewDecisionPolicy(new RiskLevelRanker()),
            new ReviewTaskFailureOutcomePolicy(),
            new ReviewTaskDurationPolicy()
        ),
        new ReviewExecutionClock()
    );

    @Test
    void appliesCompletedTaskStateAndReleasesOwnedClaim() {
        ReviewTask task = reviewingTask();
        when(reviewTaskMapper.update(any())).thenReturn(1);

        ReviewExecutionTaskTerminalWriter.CompletedTaskWrite result = writer.applyCompleted(
            task,
            ReviewResult.completed("LOW", List.of()),
            LocalDateTime.parse("2026-06-20T10:00:00"),
            "claim-1"
        );

        assertThat(result.humanReviewRequired()).isFalse();
        assertThat(result.finishedAt()).isNotNull();
        assertThat(task.getStatus()).isEqualTo("COMPLETED");
        assertThat(task.getReviewClaimedAt()).isNull();
        assertThat(task.getReviewClaimedBy()).isNull();
        org.mockito.ArgumentCaptor<UpdateWrapper<ReviewTask>> wrapperCaptor =
            org.mockito.ArgumentCaptor.captor();
        verify(reviewTaskMapper).update(wrapperCaptor.capture());
        assertThat(wrapperCaptor.getValue().getSqlSet()).contains(
            "status",
            "risk_level",
            "llm_status",
            "llm_provider",
            "human_review_status",
            "finished_at",
            "duration_seconds",
            "review_claimed_at",
            "review_claimed_by"
        );
        verify(reviewTaskMapper, never()).updateById(task);
    }

    @Test
    void throwsWhenCompletedClaimWasLostBeforePersistingTerminalState() {
        ReviewTask task = reviewingTask();
        when(reviewTaskMapper.update(any())).thenReturn(0);

        assertThatThrownBy(() -> writer.applyCompleted(
            task,
            ReviewResult.completed("LOW", List.of()),
            LocalDateTime.parse("2026-06-20T10:00:00"),
            "claim-1"
        ))
            .isInstanceOf(ReviewTaskClaimLostException.class);
        verify(reviewTaskMapper, never()).updateById(task);
    }

    @Test
    void appliesFailedTaskStateOnlyWhenClaimIsStillOwned() {
        ReviewTask task = reviewingTask();
        when(reviewTaskMapper.update(any())).thenReturn(1);

        ReviewExecutionTaskTerminalWriter.FailedTaskWrite result = writer.applyFailed(
            task,
            LocalDateTime.parse("2026-06-20T10:00:00"),
            "claim-1"
        );

        assertThat(result.applied()).isTrue();
        assertThat(result.failedAt()).isNotNull();
        assertThat(task.getStatus()).isEqualTo("FAILED");
        assertThat(task.getRiskLevel()).isEqualTo("HIGH");
        assertThat(task.getLlmStatus()).isEqualTo("FAILED");
        assertThat(task.getReviewClaimedAt()).isNull();
        assertThat(task.getReviewClaimedBy()).isNull();
        verify(reviewTaskMapper).update(any());
        verify(reviewTaskMapper, never()).updateById(task);
    }

    @Test
    void skipsFailedTaskUpdateWhenClaimWasLost() {
        ReviewTask task = reviewingTask();
        when(reviewTaskMapper.update(any())).thenReturn(0);

        ReviewExecutionTaskTerminalWriter.FailedTaskWrite result = writer.applyFailed(
            task,
            LocalDateTime.parse("2026-06-20T10:00:00"),
            "claim-1"
        );

        assertThat(result.applied()).isFalse();
        verify(reviewTaskMapper, never()).updateById(task);
    }

    private ReviewTask reviewingTask() {
        ReviewTask task = new ReviewTask();
        task.setId(42L);
        task.setStatus("REVIEWING");
        task.setRiskLevel("INFO");
        task.setLlmStatus("PENDING");
        task.setReviewClaimedAt(LocalDateTime.parse("2026-06-20T10:00:00"));
        task.setReviewClaimedBy("claim-1");
        return task;
    }
}
