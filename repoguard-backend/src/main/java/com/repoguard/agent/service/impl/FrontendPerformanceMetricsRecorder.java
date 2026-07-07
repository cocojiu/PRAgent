package com.repoguard.agent.service.impl;

import com.repoguard.agent.observability.RepoGuardMetrics;
import java.time.Duration;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class FrontendPerformanceMetricsRecorder {

    private final RepoGuardMetrics metrics;

    public FrontendPerformanceMetricsRecorder(RepoGuardMetrics metrics) {
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    public void recordApiWaterfallRequest(
        Duration duration,
        String route,
        String operation,
        String path,
        String method,
        String status,
        String result
    ) {
        metrics.frontendApiWaterfallRequest(duration, route, operation, path, method, status, result);
    }

    public void recordLongTask(Duration duration, String route) {
        metrics.frontendLongTask(duration, route);
    }
}
