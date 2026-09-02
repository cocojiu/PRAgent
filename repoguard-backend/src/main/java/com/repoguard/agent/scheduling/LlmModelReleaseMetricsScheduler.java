package com.repoguard.agent.scheduling;

import com.repoguard.agent.config.SchedulerRuntimeEnabled;
import com.repoguard.agent.review.quality.LlmModelReleaseMetricsService;
import com.repoguard.agent.tenancy.TenantScheduledTaskRunner;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically materializes tenant-scoped release runtime evidence. */
@Component
@SchedulerRuntimeEnabled
public class LlmModelReleaseMetricsScheduler {

    private final TenantScheduledTaskRunner tenantRunner;
    private final LlmModelReleaseMetricsService metricsService;

    public LlmModelReleaseMetricsScheduler(
        TenantScheduledTaskRunner tenantRunner,
        LlmModelReleaseMetricsService metricsService
    ) {
        this.tenantRunner = tenantRunner;
        this.metricsService = metricsService;
    }

    @Scheduled(cron = "${repoguard.review.llm-release-metrics-cron:0 5 * * * *}")
    public void collectReleaseRuntimeMetrics() {
        tenantRunner.runForEachActiveTenant("llm_release_runtime_metrics", metricsService::collectCurrentWindow);
    }
}
