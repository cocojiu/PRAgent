package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

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
    private final FrontendPerformanceObservationServiceImpl service =
        new FrontendPerformanceObservationServiceImpl(new RepoGuardMetrics(meterRegistry));

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
        RepoGuardMetrics metrics = new RepoGuardMetrics(meterRegistry);
        ObservabilityThresholdProperties properties = new ObservabilityThresholdProperties();
        properties.setFrontendApiDurationMs(20);
        properties.setFrontendLongTaskMs(40);
        FrontendPerformanceObservationServiceImpl thresholdService = new FrontendPerformanceObservationServiceImpl(
            metrics,
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
                12L,
                48L
            )),
            List.of(new FrontendLongTaskItemDto(90L, 83L))
        ));

        assertThat(meterRegistry.find("repoguard.observability.threshold.exceeded")
            .tag("signal", "frontend_api_duration")
            .tag("subject", "overview")
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
        RepoGuardMetrics metrics = new RepoGuardMetrics(meterRegistry);
        ObservabilityThresholdProperties properties = new ObservabilityThresholdProperties();
        properties.setFrontendApiDurationMs(2000);
        properties.setFrontendLongTaskMs(200);
        properties.setFrontendApiDurationMsByRoute(Map.of("overview", 1200L));
        properties.setFrontendLongTaskMsByRoute(Map.of("overview", 120L));
        FrontendPerformanceObservationServiceImpl thresholdService = new FrontendPerformanceObservationServiceImpl(
            metrics,
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
                12L,
                1300L
            )),
            List.of(new FrontendLongTaskItemDto(90L, 130L))
        ));

        assertThat(meterRegistry.find("repoguard.observability.threshold.exceeded")
            .tag("signal", "frontend_api_duration")
            .tag("subject", "overview")
            .counter()
            .count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("repoguard.observability.threshold.exceeded")
            .tag("signal", "frontend_long_task")
            .tag("subject", "overview")
            .counter()
            .count()).isEqualTo(1.0);
    }
}
