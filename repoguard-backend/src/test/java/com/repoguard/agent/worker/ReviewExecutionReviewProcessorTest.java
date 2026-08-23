package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubPullRequestHeadChangedException;
import com.repoguard.agent.review.PullRequestChangedFile;
import com.repoguard.agent.review.PullRequestDiff;
import com.repoguard.agent.review.PullRequestDiffTruncation;
import com.repoguard.agent.review.PullRequestReviewer;
import com.repoguard.agent.review.ReviewBudgetExceededException;
import com.repoguard.agent.review.ReviewDeadline;
import com.repoguard.agent.review.ReviewResult;
import com.repoguard.agent.review.RuleBasedPullRequestReviewer;
import com.repoguard.agent.review.execution.LargePullRequestDegradationProperties;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewExecutionReviewProcessorTest {

    private final PullRequestReviewer reviewer = org.mockito.Mockito.mock(PullRequestReviewer.class);
    private final RuleBasedPullRequestReviewer ruleBasedReviewer = org.mockito.Mockito.mock(
        RuleBasedPullRequestReviewer.class
    );
    private final LargePullRequestDegradationProperties properties = new LargePullRequestDegradationProperties();

    @Test
    void delegatesDeadlineAwareAndLegacyReviewsToTheirExpectedContracts() {
        ReviewTask task = task("head-a");
        PullRequestDiff diff = diff("head-a", List.of());
        ReviewDeadline deadline = ReviewDeadline.unlimited();
        ReviewResult expected = ReviewResult.completed("LOW", List.of());
        when(reviewer.review(task, diff, deadline)).thenReturn(expected);
        when(reviewer.review(task, diff)).thenReturn(expected);

        ReviewExecutionReviewProcessor deadlineAware = new ReviewExecutionReviewProcessor(
            reviewer,
            ruleBasedReviewer,
            properties
        );
        ReviewExecutionReviewProcessor legacy = new ReviewExecutionReviewProcessor(reviewer);

        assertThat(deadlineAware.review(task, diff, deadline)).isSameAs(expected);
        assertThat(legacy.review(task, diff, deadline)).isSameAs(expected);
        verify(reviewer).review(task, diff, deadline);
        verify(reviewer).review(task, diff);
    }

    @Test
    void degradesOversizedPullRequestsToDeterministicReview() {
        properties.setEnabled(true);
        properties.setMaxFilesForLlm(1);
        properties.setMaxChangesForLlm(2);
        ReviewTask task = task("head-a");
        PullRequestDiff diff = diff("head-a", List.of(
            new PullRequestChangedFile("A.java", "modified", 3, 1, "+a"),
            new PullRequestChangedFile("B.java", "modified", null, -2, "+b")
        ));
        ReviewDeadline deadline = ReviewDeadline.unlimited();
        when(ruleBasedReviewer.review(diff, deadline)).thenReturn(ReviewResult.completed("MEDIUM", List.of()));
        ReviewExecutionReviewProcessor processor = new ReviewExecutionReviewProcessor(
            reviewer,
            ruleBasedReviewer,
            properties
        );

        ReviewResult result = processor.review(task, diff, deadline);

        assertThat(result.statusDetail()).contains("single-server LLM capacity envelope");
        assertThat(result.llmPromptSummary()).contains("largePrDegraded=true", "files=2", "changes=4");
        verify(reviewer, never()).review(any(), any(), any());
    }

    @Test
    void annotatesTruncatedDiffsWithoutChangingCompleteDiffs() {
        ReviewExecutionReviewProcessor processor = new ReviewExecutionReviewProcessor(reviewer);
        ReviewResult original = ReviewResult.completed("LOW", List.of());
        PullRequestDiff full = diff("head-a", List.of());
        PullRequestDiff truncated = new PullRequestDiff(
            "owner",
            "repo",
            3,
            "head-a",
            List.of(),
            new PullRequestDiffTruncation(
                List.of(PullRequestDiffTruncation.Reason.MAX_FILES, PullRequestDiffTruncation.Reason.TOTAL_TIMEOUT),
                2,
                10,
                4096
            )
        );

        assertThat(processor.applyDiffBudgetOutcome(full, original)).isSameAs(original);
        ReviewResult adjusted = processor.applyDiffBudgetOutcome(truncated, original);
        assertThat(adjusted.statusDetail()).contains("Pull request diff truncated");
        assertThat(adjusted.llmPromptSummary()).contains("max_files,total_timeout");
    }

    @Test
    void validatesReviewInputAgainstClaimedCommit() {
        ReviewExecutionReviewProcessor processor = new ReviewExecutionReviewProcessor(reviewer);
        ReviewTask task = task(" head-a ");

        processor.ensureDiffMatchesTask(task, diff("HEAD-A", List.of()));
        assertThatThrownBy(() -> processor.ensureDiffMatchesTask(task, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("GitHub pull request diff is unavailable");
        task.setCommitSha(" ");
        assertThatThrownBy(() -> processor.ensureDiffMatchesTask(task, diff("head-a", List.of())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Review task commit SHA is unavailable");
        task.setCommitSha("head-a");
        assertThatThrownBy(() -> processor.ensureDiffMatchesTask(task, diff(" ", List.of())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("GitHub pull request diff head SHA is unavailable");
        assertThatThrownBy(() -> processor.ensureDiffMatchesTask(task, diff("head-b", List.of())))
            .isInstanceOf(GithubPullRequestHeadChangedException.class);
    }

    @Test
    void extractsBudgetStageAndCreatesCommitBoundEmptyDiff() {
        ReviewExecutionReviewProcessor processor = new ReviewExecutionReviewProcessor(reviewer);
        assertThat(processor.budgetStage(null)).isNull();
        assertThat(processor.budgetStage(ReviewResult.completed("LOW", List.of()))).isNull();
        assertThat(processor.budgetStage(ReviewResult.fallback(
            "MEDIUM",
            ReviewBudgetExceededException.CATEGORY + ":llm",
            List.of()
        ))).isEqualTo("llm");
        assertThat(processor.budgetStage(ReviewResult.fallback(
            "MEDIUM",
            ReviewBudgetExceededException.CATEGORY + ":unknown",
            List.of()
        ))).isEqualTo("review");

        PullRequestDiff empty = processor.emptyDiff(task("head-a"));
        assertThat(empty.owner()).isEqualTo("owner");
        assertThat(empty.repository()).isEqualTo("repo");
        assertThat(empty.prNumber()).isEqualTo(3);
        assertThat(empty.headSha()).isEqualTo("head-a");
        assertThat(empty.files()).isEmpty();
    }

    private ReviewTask task(String commitSha) {
        ReviewTask task = new ReviewTask();
        task.setId(42L);
        task.setOrganization("owner");
        task.setRepository("repo");
        task.setPrNumber(3);
        task.setCommitSha(commitSha);
        return task;
    }

    private PullRequestDiff diff(String headSha, List<PullRequestChangedFile> files) {
        return new PullRequestDiff("owner", "repo", 3, headSha, files);
    }
}
