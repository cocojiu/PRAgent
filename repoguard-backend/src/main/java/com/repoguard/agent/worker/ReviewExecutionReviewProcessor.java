package com.repoguard.agent.worker;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubPullRequestHeadChangedException;
import com.repoguard.agent.review.PullRequestDiff;
import com.repoguard.agent.review.PullRequestReviewer;
import com.repoguard.agent.review.ReviewBudgetExceededException;
import com.repoguard.agent.review.ReviewDeadline;
import com.repoguard.agent.review.ReviewResult;
import com.repoguard.agent.review.RuleBasedPullRequestReviewer;
import com.repoguard.agent.review.execution.LargePullRequestDegradationProperties;
import java.util.List;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class ReviewExecutionReviewProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger(ReviewExecutionReviewProcessor.class);

    private final PullRequestReviewer pullRequestReviewer;
    private final RuleBasedPullRequestReviewer ruleBasedReviewer;
    private final LargePullRequestDegradationProperties largePullRequestProperties;
    private final boolean deadlineAware;

    @Autowired
    ReviewExecutionReviewProcessor(
        PullRequestReviewer pullRequestReviewer,
        RuleBasedPullRequestReviewer ruleBasedReviewer,
        LargePullRequestDegradationProperties largePullRequestProperties
    ) {
        this(
            pullRequestReviewer,
            Objects.requireNonNull(ruleBasedReviewer, "ruleBasedReviewer"),
            Objects.requireNonNull(largePullRequestProperties, "largePullRequestProperties"),
            true
        );
    }

    ReviewExecutionReviewProcessor(PullRequestReviewer pullRequestReviewer) {
        this(pullRequestReviewer, null, null, false);
    }

    private ReviewExecutionReviewProcessor(
        PullRequestReviewer pullRequestReviewer,
        RuleBasedPullRequestReviewer ruleBasedReviewer,
        LargePullRequestDegradationProperties largePullRequestProperties,
        boolean deadlineAware
    ) {
        this.pullRequestReviewer = Objects.requireNonNull(pullRequestReviewer, "pullRequestReviewer");
        this.ruleBasedReviewer = ruleBasedReviewer;
        this.largePullRequestProperties = largePullRequestProperties;
        this.deadlineAware = deadlineAware;
    }

    ReviewResult review(ReviewTask task, PullRequestDiff diff, ReviewDeadline deadline) {
        if (shouldDegradeLargePullRequest(diff)) {
            int changes = totalChanges(diff);
            LOGGER.warn(
                "Large pull request degraded to deterministic review taskId={} files={} changes={} operation=large_pr_degradation",
                task.getId(),
                diff.files().size(),
                changes
            );
            return ruleBasedReviewer.review(diff, deadline).withIncompleteInput(
                "Large pull request exceeded the single-server LLM capacity envelope",
                "largePrDegraded=true; files=" + diff.files().size() + "; changes=" + changes
            );
        }
        return deadlineAware
            ? pullRequestReviewer.review(task, diff, deadline)
            : pullRequestReviewer.review(task, diff);
    }

    ReviewResult applyDiffBudgetOutcome(PullRequestDiff diff, ReviewResult reviewResult) {
        if (!diff.truncated()) {
            return reviewResult;
        }
        return reviewResult.withIncompleteInput(
            diff.truncation().summary(),
            "diffTruncated=true; diffTruncationReasons=" + diff.truncation().reasons().stream()
                .map(reason -> reason.code())
                .reduce((left, right) -> left + "," + right)
                .orElse("unknown")
        );
    }

    void ensureDiffMatchesTask(ReviewTask task, PullRequestDiff diff) {
        if (diff == null) {
            throw new IllegalStateException("GitHub pull request diff is unavailable");
        }
        if (!StringUtils.hasText(task.getCommitSha())) {
            throw new IllegalStateException("Review task commit SHA is unavailable");
        }
        if (!StringUtils.hasText(diff.headSha())) {
            throw new IllegalStateException("GitHub pull request diff head SHA is unavailable");
        }
        String expectedHeadSha = task.getCommitSha().trim();
        String diffHeadSha = diff.headSha().trim();
        if (!expectedHeadSha.equalsIgnoreCase(diffHeadSha)) {
            throw new GithubPullRequestHeadChangedException(expectedHeadSha, diffHeadSha);
        }
    }

    String budgetStage(ReviewResult result) {
        String detail = result == null ? null : result.statusDetail();
        if (detail == null || !detail.contains(ReviewBudgetExceededException.CATEGORY)) {
            return null;
        }
        for (String stage : List.of("diff_fetch", "review_context", "llm", "rule_scan", "persist")) {
            if (detail.contains(ReviewBudgetExceededException.CATEGORY + ":" + stage)) {
                return stage;
            }
        }
        return "review";
    }

    PullRequestDiff emptyDiff(ReviewTask task) {
        return new PullRequestDiff(
            task.getOrganization(),
            task.getRepository(),
            task.getPrNumber(),
            task.getCommitSha(),
            List.of()
        );
    }

    private boolean shouldDegradeLargePullRequest(PullRequestDiff diff) {
        return ruleBasedReviewer != null
            && largePullRequestProperties != null
            && largePullRequestProperties.isEnabled()
            && (diff.files().size() > largePullRequestProperties.getMaxFilesForLlm()
                || totalChanges(diff) > largePullRequestProperties.getMaxChangesForLlm());
    }

    private int totalChanges(PullRequestDiff diff) {
        long total = diff.files().stream()
            .mapToLong(file -> Math.max(0, file.additions() == null ? 0 : file.additions())
                + Math.max(0, file.deletions() == null ? 0 : file.deletions()))
            .sum();
        return (int) Math.min(total, Integer.MAX_VALUE);
    }
}
