package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repoguard.agent.dto.FrontendApiWaterfallItemDto;
import com.repoguard.agent.dto.FrontendLongTaskItemDto;
import com.repoguard.agent.dto.FrontendPerformanceReportRequest;
import com.repoguard.agent.observability.ObservabilityThresholdMonitor;
import com.repoguard.agent.observability.ObservabilityThresholdProperties;
import com.repoguard.agent.observability.RepoGuardMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class FrontendPerformanceObservationServiceImplTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final RepoGuardMetrics metrics = new RepoGuardMetrics(
        meterRegistry,
        new com.repoguard.agent.worker.ReviewExecutionFailureClassifier()
    );
    private final FrontendPerformanceMetricsRecorder metricsRecorder = new FrontendPerformanceMetricsRecorder(metrics);
    private final FrontendPerformanceObservationServiceImpl service =
        new FrontendPerformanceObservationServiceImpl(metricsRecorder, thresholdMonitor(metrics));

    @Test
    void constructorRejectsMissingMetricsRecorder() {
        assertThatThrownBy(() -> new FrontendPerformanceObservationServiceImpl(null, thresholdMonitor(metrics)))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("metricsRecorder");
    }

    @Test
    void constructorRejectsMissingThresholdMonitor() {
        assertThatThrownBy(() -> new FrontendPerformanceObservationServiceImpl(metricsRecorder, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("thresholdMonitor");
    }

    @Test
    void recordsFrontendApiWaterfallAndLongTaskMetrics() {
        service.record(new FrontendPerformanceReportRequest(
            "overview",
            List.of(new FrontendApiWaterfallItemDto(
                "fetchDashboardSummary",
                "/api/v1/dashboard/summary",
                "GET",
                200,
                "success",
                "trace-summary-1",
                2048L,
                12L,
                48L
            )),
            List.of(new FrontendLongTaskItemDto(90L, 83L))
        ));

        assertThat(meterRegistry.find("repoguard.frontend.api.waterfall.request")
            .tag("route", "overview")
            .tag("operation", "fetchdashboardsummary")
            .tag("path", "/api/v1/dashboard/summary")
            .tag("method", "GET")
            .tag("status", "200")
            .tag("result", "success")
            .counter()
            .count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("repoguard.frontend.api.waterfall.duration")
            .tag("route", "overview")
            .tag("operation", "fetchdashboardsummary")
            .tag("path", "/api/v1/dashboard/summary")
            .tag("method", "GET")
            .tag("status", "200")
            .tag("result", "success")
            .timer()
            .count()).isEqualTo(1);
        assertThat(meterRegistry.find("repoguard.frontend.long_task")
            .tag("route", "overview")
            .counter()
            .count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("repoguard.frontend.long_task.duration")
            .tag("route", "overview")
            .timer()
            .totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isEqualTo(83.0);
    }

    @Test
    void recordsThresholdSignalsForSlowFrontendApiAndLongTask() {
        RepoGuardMetrics metrics = new RepoGuardMetrics(
            meterRegistry,
            new com.repoguard.agent.worker.ReviewExecutionFailureClassifier()
        );
        ObservabilityThresholdProperties properties = new ObservabilityThresholdProperties();
        properties.setFrontendApiDurationMs(20);
        properties.setFrontendLongTaskMs(40);
        FrontendPerformanceObservationServiceImpl thresholdService = new FrontendPerformanceObservationServiceImpl(
            new FrontendPerformanceMetricsRecorder(metrics),
            new ObservabilityThresholdMonitor(metrics, properties)
        );

        thresholdService.record(new FrontendPerformanceReportRequest(
            "overview",
            List.of(new FrontendApiWaterfallItemDto(
                "fetchDashboardSummary",
                "/api/v1/dashboard/summary",
                "GET",
                200,
                "success",
                "trace-summary-2",
                2048L,
                12L,
                48L
            )),
            List.of(new FrontendLongTaskItemDto(90L, 83L))
        ));

        assertThat(meterRegistry.find("repoguard.observability.threshold.exceeded")
            .tag("signal", "frontend_api_duration")
            .tag("subject", "overview_fetchdashboardsummary_api_v1_dashboard_summary")
            .counter()
            .count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("repoguard.observability.threshold.exceeded")
            .tag("signal", "frontend_long_task")
            .tag("subject", "overview")
            .counter()
            .count()).isEqualTo(1.0);
    }

    @Test
    void appliesRouteSpecificFrontendPerformanceBudgets() {
        RepoGuardMetrics metrics = new RepoGuardMetrics(
            meterRegistry,
            new com.repoguard.agent.worker.ReviewExecutionFailureClassifier()
        );
        ObservabilityThresholdProperties properties = new ObservabilityThresholdProperties();
        properties.setFrontendApiDurationMs(2000);
        properties.setFrontendLongTaskMs(200);
        properties.setFrontendApiDurationMsByRoute(Map.of(
            "overview", 1200L,
            "task-detail", 1500L,
            "message-queue", 1200L,
            "notification-ops", 1200L
        ));
        properties.setFrontendLongTaskMsByRoute(Map.of(
            "overview", 120L,
            "task-detail", 150L,
            "message-queue", 150L,
            "notification-ops", 150L
        ));
        FrontendPerformanceObservationServiceImpl thresholdService = new FrontendPerformanceObservationServiceImpl(
            new FrontendPerformanceMetricsRecorder(metrics),
            new ObservabilityThresholdMonitor(metrics, properties)
        );

        recordSlowFrontendRoute(thresholdService, "overview", 1300L, 130L);
        recordSlowFrontendRoute(thresholdService, "task-detail", 1600L, 160L);
        recordSlowFrontendRoute(thresholdService, "message-queue", 1300L, 160L);
        recordSlowFrontendRoute(thresholdService, "notification-ops", 1300L, 160L);

        assertFrontendThresholdExceeded("overview", "overview_fetchroutedata_api_v1_overview");
        assertFrontendThresholdExceeded("task-detail", "task-detail_fetchroutedata_api_v1_task-detail");
        assertFrontendThresholdExceeded("message-queue", "message-queue_fetchroutedata_api_v1_message-queue");
        assertFrontendThresholdExceeded("notification-ops", "notification-ops_fetchroutedata_api_v1_notification-ops");
    }

    private void recordSlowFrontendRoute(
        FrontendPerformanceObservationServiceImpl thresholdService,
        String route,
        long apiDurationMs,
        long longTaskMs
    ) {
        thresholdService.record(new FrontendPerformanceReportRequest(
            route,
            List.of(new FrontendApiWaterfallItemDto(
                "fetchRouteData",
                "/api/v1/" + route,
                "GET",
                200,
                "success",
                "trace-route",
                4096L,
                12L,
                apiDurationMs
            )),
            List.of(new FrontendLongTaskItemDto(90L, longTaskMs))
        ));
    }

    private void assertFrontendThresholdExceeded(String route, String apiSubject) {
        assertThat(meterRegistry.find("repoguard.observability.threshold.exceeded")
            .tag("signal", "frontend_api_duration")
            .tag("subject", apiSubject)
            .counter()
            .count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("repoguard.observability.threshold.exceeded")
            .tag("signal", "frontend_long_task")
            .tag("subject", route)
            .counter()
            .count()).isEqualTo(1.0);
    }

    private ObservabilityThresholdMonitor thresholdMonitor(RepoGuardMetrics metrics) {
        return new ObservabilityThresholdMonitor(metrics, new ObservabilityThresholdProperties());
    }
}
