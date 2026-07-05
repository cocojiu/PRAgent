package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.dto.FrontendApiWaterfallItemDto;
import com.repoguard.agent.dto.FrontendLongTaskItemDto;
import com.repoguard.agent.dto.FrontendPerformanceReportRequest;
import com.repoguard.agent.observability.RepoGuardMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
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
            .tag("method", "GET")
            .tag("status", "200")
            .tag("result", "success")
            .counter()
            .count()).isEqualTo(1.0);
        assertThat(meterRegistry.find("repoguard.frontend.api.waterfall.duration")
            .tag("route", "overview")
            .tag("operation", "fetchdashboardsummary")
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
}
