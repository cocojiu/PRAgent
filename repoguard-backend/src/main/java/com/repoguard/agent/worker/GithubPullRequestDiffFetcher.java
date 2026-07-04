package com.repoguard.agent.worker;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.external.ExternalCallException;
import com.repoguard.agent.github.GithubPullRequestClient;
import com.repoguard.agent.github.GithubPullRequestDiff;
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

    GithubPullRequestDiffFetcher(
        GithubPullRequestClient githubPullRequestClient,
        ReviewExecutionMetricsRecorder metricsRecorder,
        ReviewExecutionClock clock,
        ReviewLogContextFormatter logContextFormatter
    ) {
        this.githubPullRequestClient = githubPullRequestClient;
        this.metricsRecorder = metricsRecorder;
        this.clock = clock;
        this.logContextFormatter = Objects.requireNonNull(logContextFormatter, "logContextFormatter");
    }

    GithubPullRequestDiff fetch(ReviewTask task) {
        var startedAt = clock.now();
        try {
            LOGGER.info(
                "GitHub diff fetch started taskId={} repository={} prNumber={} operation=github_diff_fetch",
                task.getId(),
                logContextFormatter.repositorySlug(task),
                task.getPrNumber()
            );
            GithubPullRequestDiff diff = githubPullRequestClient.fetchPullRequestDiff(task);
            Duration duration = Duration.between(startedAt, clock.now());
            metricsRecorder.recordGithubDiffFetch(duration, "success");
            LOGGER.info(
                "GitHub diff fetch completed taskId={} repository={} prNumber={} operation=github_diff_fetch result=success durationMs={} files={}",
                task.getId(),
                logContextFormatter.repositorySlug(task),
                task.getPrNumber(),
                duration.toMillis(),
                diff.files() == null ? 0 : diff.files().size()
            );
            return diff;
        } catch (RuntimeException ex) {
            Duration duration = Duration.between(startedAt, clock.now());
            metricsRecorder.recordGithubDiffFetch(duration, "failed");
            LOGGER.warn(
                "GitHub diff fetch failed taskId={} repository={} prNumber={} operation=github_diff_fetch result=failed failureCategory={} exceptionType={} durationMs={}",
                task.getId(),
                logContextFormatter.repositorySlug(task),
                task.getPrNumber(),
                failureCategory(ex),
                ex.getClass().getName(),
                duration.toMillis()
            );
            throw ex;
        }
    }

    private String failureCategory(RuntimeException ex) {
        if (ex instanceof ExternalCallException externalCallException) {
            return externalCallException.getCategory();
        }
        return ex.getClass().getSimpleName();
    }
}
