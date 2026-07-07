package com.repoguard.agent.service.impl;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.dto.DataRetentionCleanupResponse;
import com.repoguard.agent.observability.RepoGuardMetrics;
import java.util.Objects;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

@Component
public class DataRetentionMetricsRecorder {

    private final RepoGuardMetrics metrics;

    public DataRetentionMetricsRecorder(RepoGuardMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
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
        metrics.dataRetentionCleanupFailed(executed, failureReason(ex));
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
