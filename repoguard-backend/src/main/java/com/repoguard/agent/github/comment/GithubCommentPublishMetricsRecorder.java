package com.repoguard.agent.github.comment;

import com.repoguard.agent.observability.RepoGuardMetrics;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class GithubCommentPublishMetricsRecorder {

    private final RepoGuardMetrics metrics;

    public GithubCommentPublishMetricsRecorder(RepoGuardMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public void recordItems(int succeededCount, int failedCount, int skippedCount) {
        record("success", succeededCount);
        record("failed", failedCount);
        record("skipped", skippedCount);
    }

    public void recordDuration(LocalDateTime startedAt, boolean failed) {
        metrics.githubCommentPublishDuration(
            Duration.between(startedAt, LocalDateTime.now()),
            failed ? "failed" : "success"
        );
    }

    private void record(String status, int count) {
        for (int i = 0; i < Math.max(0, count); i++) {
            metrics.githubCommentPublished(status);
        }
    }
}
