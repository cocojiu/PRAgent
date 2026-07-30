package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.review.EnforcementMode;
import com.repoguard.agent.review.ReviewFindingResult;
import com.repoguard.agent.review.ReviewResult;
import com.repoguard.agent.review.ReviewTaskStateMachine;
import com.repoguard.agent.review.RiskLevelRanker;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewTaskCompletionApplierTest {

    private final ReviewTaskCompletionApplier applier =
        new ReviewTaskCompletionApplier(
            new ReviewTaskStateMachine(),
            new ReviewHumanReviewDecisionPolicy(new RiskLevelRanker()),
            new ReviewTaskFailureOutcomePolicy(),
            new ReviewTaskDurationPolicy()
        );

    @Test
    void appliesCompletedReviewWithoutHumanReview() {
        ReviewTask task = new ReviewTask();
        LocalDateTime startedAt = LocalDateTime.of(2026, 6, 20, 10, 0);
        LocalDateTime finishedAt = startedAt.plusSeconds(75);

        boolean humanReviewRequired = applier.applyCompleted(
            task,
            ReviewResult.completed(
                "LOW",
                List.of(),
                "openai",
                "gpt-test",
                1234,
                "PARSED",
                "prompt summary",
                100,
                50,
                150,
                BigDecimal.valueOf(0.0123)
            ),
            startedAt,
            finishedAt
        );

        assertThat(humanReviewRequired).isFalse();
        assertThat(task.getStatus()).isEqualTo("COMPLETED");
        assertThat(task.getRiskLevel()).isEqualTo("LOW");
        assertThat(task.getAssessmentStatus()).isEqualTo("COMPLETE");
        assertThat(task.getLlmStatus()).isEqualTo("COMPLETED");
        assertThat(task.getLlmProvider()).isEqualTo("openai");
        assertThat(task.getLlmModel()).isEqualTo("gpt-test");
        assertThat(task.getLlmDurationMs()).isEqualTo(1234);
        assertThat(task.getLlmParseStatus()).isEqualTo("parsed");
        assertThat(task.getLlmPromptSummary()).isEqualTo("prompt summary");
        assertThat(task.getLlmPromptTokens()).isEqualTo(100);
        assertThat(task.getLlmCompletionTokens()).isEqualTo(50);
        assertThat(task.getLlmTotalTokens()).isEqualTo(150);
        assertThat(task.getLlmEstimatedCost()).isEqualByComparingTo("0.0123");
        assertThat(task.getHumanReviewRequired()).isFalse();
        assertThat(task.getHumanReviewStatus()).isEqualTo("NOT_REQUIRED");
        assertThat(task.getFinishedAt()).isEqualTo(finishedAt);
        assertThat(task.getDurationSeconds()).isEqualTo(75);
    }

    @Test
    void appliesCompletedReviewWithHumanReviewRequired() {
        ReviewTask task = new ReviewTask();
        task.setHumanReviewNote("old note");
        task.setHumanReviewBy("reviewer");
        task.setHumanReviewedAt(LocalDateTime.now());

        boolean humanReviewRequired = applier.applyCompleted(
            task,
            ReviewResult.completed("HIGH", List.of(new ReviewFindingResult(
                "HIGH",
                "RULE",
                "RG-AUTH-001",
                "src/AdminController.java",
                12,
                "Administrative endpoint has no authorization guard",
                "Require an administrator role",
                "HIGH",
                "Verified added-line evidence",
                "Unauthorized configuration changes",
                "Add an authorization annotation",
                true,
                "ACCESS_CONTROL",
                EnforcementMode.BLOCK.name(),
                "verified_blocking_rule"
            ))),
            LocalDateTime.of(2026, 6, 20, 10, 0),
            LocalDateTime.of(2026, 6, 20, 10, 1)
        );

        assertThat(humanReviewRequired).isTrue();
        assertThat(task.getStatus()).isEqualTo("PENDING_HUMAN_REVIEW");
        assertThat(task.getAssessmentStatus()).isEqualTo("COMPLETE");
        assertThat(task.getHumanReviewRequired()).isTrue();
        assertThat(task.getHumanReviewStatus()).isEqualTo("PENDING");
        assertThat(task.getHumanReviewNote()).isNull();
        assertThat(task.getHumanReviewBy()).isNull();
        assertThat(task.getHumanReviewedAt()).isNull();
    }

    @Test
    void appliesFailedReviewState() {
        ReviewTask task = new ReviewTask();
        LocalDateTime startedAt = LocalDateTime.of(2026, 6, 20, 10, 0);
        LocalDateTime failedAt = startedAt.plusSeconds(31);

        applier.applyFailed(task, startedAt, failedAt);

        assertThat(task.getStatus()).isEqualTo("FAILED");
        assertThat(task.getRiskLevel()).isEqualTo("INFO");
        assertThat(task.getAssessmentStatus()).isEqualTo("FAILED");
        assertThat(task.getLlmStatus()).isEqualTo("FAILED");
        assertThat(task.getFinishedAt()).isEqualTo(failedAt);
        assertThat(task.getDurationSeconds()).isEqualTo(31);
    }
}
