package com.repoguard.agent.worker;

import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.review.ReviewResult;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class ReviewExecutionMetricsRecorder {

    private final RepoGuardMetrics metrics;

    ReviewExecutionMetricsRecorder(RepoGuardMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    void recordCompleted(ReviewResult reviewResult, LocalDateTime startedAt, LocalDateTime finishedAt) {
        metrics.reviewTaskCompleted(reviewResult.riskLevel(), reviewResult.llmStatus());
        metrics.reviewTaskDuration(Duration.between(startedAt, finishedAt), "completed");
    }

    void recordFailed(RuntimeException ex, LocalDateTime startedAt, LocalDateTime failedAt) {
        metrics.reviewTaskFailed(ex);
        metrics.reviewTaskDuration(Duration.between(startedAt, failedAt), "failed");
    }

    void recordSuperseded(LocalDateTime startedAt, LocalDateTime supersededAt) {
        metrics.reviewTaskDuration(Duration.between(startedAt, supersededAt), "superseded");
    }

    void recordGithubDiffFetch(Duration duration, String result) {
        metrics.githubDiffDuration(duration, result);
    }

    void recordStage(Duration duration, String stage, String result) {
        metrics.reviewExecutionStageDuration(duration, stage, result);
    }
}
