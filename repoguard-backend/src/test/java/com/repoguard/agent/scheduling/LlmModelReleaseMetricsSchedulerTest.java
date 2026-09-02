package com.repoguard.agent.scheduling;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.repoguard.agent.review.quality.LlmModelReleaseMetricsService;
import com.repoguard.agent.review.quality.LlmModelReleaseService;
import com.repoguard.agent.tenancy.TenantScheduledTaskRunner;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class LlmModelReleaseMetricsSchedulerTest {

    private final TenantScheduledTaskRunner tenantRunner = org.mockito.Mockito.mock(TenantScheduledTaskRunner.class);
    private final LlmModelReleaseMetricsService metricsService = org.mockito.Mockito.mock(LlmModelReleaseMetricsService.class);
    private final LlmModelReleaseService releaseService = org.mockito.Mockito.mock(LlmModelReleaseService.class);
    private final LlmModelReleaseMetricsScheduler scheduler = new LlmModelReleaseMetricsScheduler(tenantRunner, metricsService);

    @Test
    void collectsRuntimeMetricsForEveryActiveTenant() {
        scheduler.collectReleaseRuntimeMetrics();

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(tenantRunner).runForEachActiveTenant(eq("llm_release_runtime_metrics"), taskCaptor.capture());
        taskCaptor.getValue().run();
        verify(metricsService).collectCurrentWindow();
    }

    @Test
    void scheduledCollectionAlsoSweepsDueEvaluationReportsWhenLifecycleServiceIsAvailable() {
        LlmModelReleaseMetricsScheduler scheduled = new LlmModelReleaseMetricsScheduler(
            tenantRunner, metricsService, releaseService
        );

        scheduled.collectReleaseRuntimeMetrics();

        ArgumentCaptor<Runnable> taskCaptor = ArgumentCaptor.forClass(Runnable.class);
        verify(tenantRunner).runForEachActiveTenant(eq("llm_release_runtime_metrics"), taskCaptor.capture());
        taskCaptor.getValue().run();
        verify(metricsService).collectCurrentWindow();
        verify(releaseService).expireDueEvaluationReports();
    }
}
