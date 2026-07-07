package com.repoguard.agent.service.impl;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.dto.DataRetentionCleanupResponse;
import com.repoguard.agent.observability.ObservabilityThresholdMonitor;
import com.repoguard.agent.observability.RepoGuardMetrics;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Component
public class DataRetentionMetricsRecorder {

    private final RepoGuardMetrics metrics;
    private final ObservabilityThresholdMonitor thresholdMonitor;

    public DataRetentionMetricsRecorder(
        RepoGuardMetrics metrics,
        ObservabilityThresholdMonitor thresholdMonitor
    ) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.thresholdMonitor = Objects.requireNonNull(thresholdMonitor, "thresholdMonitor");
    }

    public void record(DataRetentionCleanupResponse response) {
        if (response == null) {
            return;
        }
        metrics.dataRetentionCleanup(
            response.executed(),
            response.candidateTasks(),
            response.selectedTasks(),
            response.deletedTasks()
        );
    }

    public void recordFailure(boolean executed, RuntimeException ex) {
        String reason = failureReason(ex);
        metrics.dataRetentionCleanupFailed(executed, reason);
        thresholdMonitor.dataRetentionCleanupFailure(executed, reason);
    }

    private String failureReason(RuntimeException ex) {
        if (ex instanceof BusinessException) {
            return "bad_request";
        }
        if (ex instanceof DataAccessException) {
            return "database_error";
        }
        return "cleanup_failed";
    }
}
