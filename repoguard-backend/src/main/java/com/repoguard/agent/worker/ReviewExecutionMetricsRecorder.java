package com.repoguard.agent.worker;

import com.repoguard.agent.observability.RepoGuardMetrics;
import com.repoguard.agent.review.ReviewResult;
import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

@Component
class ReviewExecutionMetricsRecorder {

    private final RepoGuardMetrics metrics;

    ReviewExecutionMetricsRecorder(RepoGuardMetrics metrics) {
        this.metrics = metrics;
    }

    void recordCompleted(ReviewResult reviewResult, LocalDateTime startedAt, LocalDateTime finishedAt) {
        if (metrics == null) {
            return;
        }
        metrics.reviewTaskCompleted(reviewResult.riskLevel(), reviewResult.llmStatus());
        metrics.reviewTaskDuration(Duration.between(startedAt, finishedAt), "completed");
    }

    void recordFailed(RuntimeException ex, LocalDateTime startedAt, LocalDateTime failedAt) {
        if (metrics == null) {
            return;
        }
        metrics.reviewTaskFailed(ex);
        metrics.reviewTaskDuration(Duration.between(startedAt, failedAt), "failed");
    }
}
