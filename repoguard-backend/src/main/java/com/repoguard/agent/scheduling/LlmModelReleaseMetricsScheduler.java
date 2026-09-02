package com.repoguard.agent.scheduling;

import com.repoguard.agent.config.SchedulerRuntimeEnabled;
import com.repoguard.agent.review.quality.LlmModelReleaseService;
import com.repoguard.agent.review.quality.LlmModelReleaseMetricsService;
import com.repoguard.agent.review.quality.LlmModelReleaseDriftService;
import com.repoguard.agent.tenancy.TenantScheduledTaskRunner;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically materializes tenant-scoped release runtime evidence. */
@Component
@SchedulerRuntimeEnabled
public class LlmModelReleaseMetricsScheduler {

    private final TenantScheduledTaskRunner tenantRunner;
    private final LlmModelReleaseMetricsService metricsService;
    private final LlmModelReleaseService releaseService;
    private final LlmModelReleaseDriftService driftService;

    @Autowired
    public LlmModelReleaseMetricsScheduler(
        TenantScheduledTaskRunner tenantRunner,
        LlmModelReleaseMetricsService metricsService,
        LlmModelReleaseService releaseService,
        LlmModelReleaseDriftService driftService
    ) {
        this.tenantRunner = Objects.requireNonNull(tenantRunner, "tenantRunner");
        this.metricsService = Objects.requireNonNull(metricsService, "metricsService");
        this.releaseService = Objects.requireNonNull(releaseService, "releaseService");
        this.driftService = Objects.requireNonNull(driftService, "driftService");
    }

    public LlmModelReleaseMetricsScheduler(
        TenantScheduledTaskRunner tenantRunner,
        LlmModelReleaseMetricsService metricsService
    ) {
        this.tenantRunner = Objects.requireNonNull(tenantRunner, "tenantRunner");
        this.metricsService = Objects.requireNonNull(metricsService, "metricsService");
        this.releaseService = null;
        this.driftService = null;
    }

    public LlmModelReleaseMetricsScheduler(
        TenantScheduledTaskRunner tenantRunner,
        LlmModelReleaseMetricsService metricsService,
        LlmModelReleaseService releaseService
    ) {
        this.tenantRunner = Objects.requireNonNull(tenantRunner, "tenantRunner");
        this.metricsService = Objects.requireNonNull(metricsService, "metricsService");
        this.releaseService = releaseService;
        this.driftService = null;
    }

    @Scheduled(cron = "${repoguard.review.llm-release-metrics-cron:0 5 * * * *}")
    public void collectReleaseRuntimeMetrics() {
        tenantRunner.runForEachActiveTenant("llm_release_runtime_metrics", () -> {
            metricsService.collectCurrentWindow();
            if (releaseService != null) {
                releaseService.expireDueEvaluationReports();
            }
            if (driftService != null) {
                driftService.detect();
            }
        });
    }
}
