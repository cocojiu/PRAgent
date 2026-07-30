package com.repoguard.agent.worker;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.github.GithubPullRequestClient;
import com.repoguard.agent.review.PullRequestDiff;
import com.repoguard.agent.github.GithubPullRequestHeadChangedException;
import java.time.Duration;
import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class GithubPullRequestDiffFetcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(GithubPullRequestDiffFetcher.class);

    private final GithubPullRequestClient githubPullRequestClient;
    private final ReviewExecutionMetricsRecorder metricsRecorder;
    private final ReviewExecutionClock clock;
    private final ReviewLogContextFormatter logContextFormatter;
    private final ReviewExecutionFailureClassifier failureClassifier;

    GithubPullRequestDiffFetcher(
        GithubPullRequestClient githubPullRequestClient,
        ReviewExecutionMetricsRecorder metricsRecorder,
        ReviewExecutionClock clock,
        ReviewLogContextFormatter logContextFormatter,
        ReviewExecutionFailureClassifier failureClassifier
    ) {
        this.githubPullRequestClient = githubPullRequestClient;
        this.metricsRecorder = metricsRecorder;
        this.clock = clock;
        this.logContextFormatter = Objects.requireNonNull(logContextFormatter, "logContextFormatter");
        this.failureClassifier = Objects.requireNonNull(failureClassifier, "failureClassifier");
    }

    PullRequestDiff fetch(ReviewTask task) {
        var startedAt = clock.now();
        try {
            LOGGER.info(
                "GitHub diff fetch started taskId={} repository={} prNumber={} operation=github_diff_fetch",
                task.getId(),
                logContextFormatter.repositorySlug(task),
                task.getPrNumber()
            );
            PullRequestDiff diff = githubPullRequestClient.fetchPullRequestDiff(task);
            Duration duration = Duration.between(startedAt, clock.now());
            String result = diff.truncated() ? "truncated" : "success";
            metricsRecorder.recordGithubDiffFetch(duration, result);
            LOGGER.info(
                "GitHub diff fetch completed taskId={} repository={} prNumber={} operation=github_diff_fetch result={} durationMs={} files={} pages={} retainedBytes={} truncationReasons={}",
                task.getId(),
                logContextFormatter.repositorySlug(task),
                task.getPrNumber(),
                result,
                duration.toMillis(),
                diff.files().size(),
                diff.truncation().pagesFetched(),
                diff.truncation().retainedBytes(),
                diff.truncation().reasons()
            );
            return diff;
        } catch (RuntimeException ex) {
            Duration duration = Duration.between(startedAt, clock.now());
            String result = ex instanceof GithubPullRequestHeadChangedException ? "superseded" : "failed";
            metricsRecorder.recordGithubDiffFetch(duration, result);
            LOGGER.warn(
                "GitHub diff fetch ended taskId={} repository={} prNumber={} operation=github_diff_fetch result={} failureCategory={} exceptionType={} durationMs={}",
                task.getId(),
                logContextFormatter.repositorySlug(task),
                task.getPrNumber(),
                result,
                failureClassifier.failureCategory(ex),
                ex.getClass().getName(),
                duration.toMillis()
            );
            throw ex;
        }
    }
}
