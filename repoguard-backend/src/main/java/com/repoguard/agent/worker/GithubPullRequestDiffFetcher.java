package com.repoguard.agent.worker;

import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.external.ExternalCallException;
import com.repoguard.agent.github.GithubPullRequestClient;
import com.repoguard.agent.github.GithubPullRequestDiff;
import com.repoguard.agent.observability.RepoGuardMetrics;
import java.time.Duration;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
class GithubPullRequestDiffFetcher {

    private static final Logger LOGGER = LoggerFactory.getLogger(GithubPullRequestDiffFetcher.class);

    private final GithubPullRequestClient githubPullRequestClient;
    private final RepoGuardMetrics metrics;

    GithubPullRequestDiffFetcher(
        GithubPullRequestClient githubPullRequestClient,
        RepoGuardMetrics metrics
    ) {
        this.githubPullRequestClient = githubPullRequestClient;
        this.metrics = metrics;
    }

    GithubPullRequestDiff fetch(ReviewTask task) {
        LocalDateTime startedAt = LocalDateTime.now();
        try {
            LOGGER.info(
                "GitHub diff fetch started taskId={} repository={} prNumber={} operation=github_diff_fetch",
                task.getId(),
                repositorySlug(task),
                task.getPrNumber()
            );
            GithubPullRequestDiff diff = githubPullRequestClient.fetchPullRequestDiff(task);
            if (metrics != null) {
                metrics.githubDiffDuration(Duration.between(startedAt, LocalDateTime.now()), "success");
            }
            LOGGER.info(
                "GitHub diff fetch completed taskId={} repository={} prNumber={} operation=github_diff_fetch result=success durationMs={} files={}",
                task.getId(),
                repositorySlug(task),
                task.getPrNumber(),
                Duration.between(startedAt, LocalDateTime.now()).toMillis(),
                diff.files() == null ? 0 : diff.files().size()
            );
            return diff;
        } catch (RuntimeException ex) {
            if (metrics != null) {
                metrics.githubDiffDuration(Duration.between(startedAt, LocalDateTime.now()), "failed");
            }
            LOGGER.warn(
                "GitHub diff fetch failed taskId={} repository={} prNumber={} operation=github_diff_fetch result=failed failureCategory={} exceptionType={} durationMs={}",
                task.getId(),
                repositorySlug(task),
                task.getPrNumber(),
                failureCategory(ex),
                ex.getClass().getName(),
                Duration.between(startedAt, LocalDateTime.now()).toMillis()
            );
            throw ex;
        }
    }

    private String repositorySlug(ReviewTask task) {
        return safePart(task.getOrganization()) + "/" + safePart(task.getRepository());
    }

    private String safePart(String value) {
        return value == null || value.isBlank() ? "<unknown>" : value.trim();
    }

    private String failureCategory(RuntimeException ex) {
        if (ex instanceof ExternalCallException externalCallException) {
            return externalCallException.getCategory();
        }
        return ex.getClass().getSimpleName();
    }
}
