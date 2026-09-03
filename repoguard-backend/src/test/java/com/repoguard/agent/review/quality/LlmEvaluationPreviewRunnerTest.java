package com.repoguard.agent.review.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.review.LlmParseStatus;
import com.repoguard.agent.review.LlmPullRequestReviewer;
import com.repoguard.agent.review.PullRequestDiff;
import com.repoguard.agent.review.ReviewDeadline;
import com.repoguard.agent.review.ReviewFindingResult;
import com.repoguard.agent.review.ReviewResult;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class LlmEvaluationPreviewRunnerTest {

    @Test
    void reducesProductionReviewToAggregateLabelsWithoutReturningPayload() {
        LlmPullRequestReviewer reviewer = org.mockito.Mockito.mock(LlmPullRequestReviewer.class);
        LlmEvaluationPreviewRunner runner = new LlmEvaluationPreviewRunner(reviewer);
        LlmEvaluationDatasetLoader.EvaluationCase sample = sample(true, "src/App.java:2");
        ReviewResult result = ReviewResult.completed(
            "HIGH",
            List.of(
                new ReviewFindingResult("HIGH", "LLM", "RG-LLM-1", "src/App.java", 2, "message", "fix"),
                new ReviewFindingResult("LOW", "RULE", "RG-RULE-1", "src/Other.java", 3, "message", "fix")
            ),
            "openai", "gpt-test", 120, LlmParseStatus.PARSED.code(), "summary", 10, 20, 30,
            BigDecimal.valueOf(0.01)
        );
        when(reviewer.reviewForEvaluation(any(), any(), any(), eq("openai"), eq("gpt-test")))
            .thenReturn(result);

        LlmEvaluationObservation observation = runner.run(sample, "openai", "gpt-test", ReviewDeadline.unlimited());

        assertThat(observation.expectedFinding()).isTrue();
        assertThat(observation.predictedFinding()).isTrue();
        assertThat(observation.predictedSeverity()).isEqualTo("HIGH");
        assertThat(observation.anchorValid()).isTrue();
        assertThat(observation.predictionKey()).hasSize(64);
        assertThat(observation.parseSucceeded()).isTrue();
        assertThat(observation.latencyMs()).isEqualTo(120);
        assertThat(observation.totalTokens()).isEqualTo(30);
        assertThat(observation.estimatedCost()).isEqualByComparingTo("0.01");
        assertThat(observation.ruleFindingCount()).isEqualTo(1);
        assertThat(observation.llmFindingCount()).isEqualTo(1);
        assertThat(observation.sampleContext().changedLineCount()).isEqualTo(5);
        assertThat(observation.sampleContext().expectedLocationKey()).hasSize(64);
        assertThat(observation.split()).isEqualTo(LlmEvaluationObservation.EvaluationSplit.FIXED_REGRESSION);
        verify(reviewer).reviewForEvaluation(any(), any(), any(), eq("openai"), eq("gpt-test"));
    }

    @Test
    void handlesNoFindingsFallbackAndUnknownSplit() {
        LlmPullRequestReviewer reviewer = org.mockito.Mockito.mock(LlmPullRequestReviewer.class);
        LlmEvaluationPreviewRunner runner = new LlmEvaluationPreviewRunner(reviewer);
        LlmEvaluationDatasetLoader.EvaluationCase sample = sample(false, null);
        sample = new LlmEvaluationDatasetLoader.EvaluationCase(
            sample.caseId(), sample.sourceRepositoryKey(), "not-a-split", sample.language(), sample.fileTypeGroup(),
            sample.expectedLocationKey(), sample.expectedFinding(), sample.expectedSeverity(), sample.organization(),
            sample.repository(), sample.prNumber(), sample.headSha(), sample.title(), sample.branch(), sample.files(),
            null, null, null, Boolean.TRUE
        );
        when(reviewer.reviewForEvaluation(any(), any(), any(), eq("openai"), eq("gpt-test")))
            .thenReturn(ReviewResult.fallback("INFO", "unavailable", List.of()));

        LlmEvaluationObservation observation = runner.run(sample, "openai", "gpt-test", ReviewDeadline.unlimited());

        assertThat(observation.predictedFinding()).isFalse();
        assertThat(observation.predictedSeverity()).isEqualTo("NONE");
        assertThat(observation.anchorValid()).isTrue();
        assertThat(observation.predictionKey()).isEmpty();
        assertThat(observation.parseSucceeded()).isFalse();
        assertThat(observation.estimatedCost()).isZero();
        assertThat(observation.commentPublishAttempted()).isTrue();
        assertThat(observation.commentIgnored()).isTrue();
        assertThat(observation.split()).isEqualTo(LlmEvaluationObservation.EvaluationSplit.UNSPECIFIED);
    }

    @Test
    void rejectsExpiredReviewDeadlineBeforeCallingReviewer() {
        LlmPullRequestReviewer reviewer = org.mockito.Mockito.mock(LlmPullRequestReviewer.class);
        LlmEvaluationPreviewRunner runner = new LlmEvaluationPreviewRunner(reviewer);

        assertThat(org.assertj.core.api.Assertions.catchThrowable(() -> runner.run(
            sample(false, null), "openai", "gpt-test", ReviewDeadline.startingAt(0, java.time.Duration.ofNanos(1), () -> 2)
        ))).isInstanceOf(RuntimeException.class);
        org.mockito.Mockito.verifyNoInteractions(reviewer);
    }

    private LlmEvaluationDatasetLoader.EvaluationCase sample(boolean expectedFinding, String location) {
        return new LlmEvaluationDatasetLoader.EvaluationCase(
            "case-1", "repo-1", "FIXED_REGRESSION", "java", "jvm", location, expectedFinding,
            expectedFinding ? "HIGH" : "NONE", "org", "repo", 1, "head", "title", "main",
            List.of(new LlmEvaluationDatasetLoader.EvaluationFile("src/App.java", "modified", 3, 2, "patch")),
            Boolean.TRUE, Boolean.TRUE, Boolean.TRUE, Boolean.FALSE
        );
    }
}
