package com.repoguard.agent.observability;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.core.Ordered;

class ObservabilityFilterConfigTest {

    @Test
    void traceFilterRunsBeforeApiObservationAndSecurityFilters() {
        var traceRegistration = new TraceIdFilterConfig().traceIdFilterRegistration();
        RepoGuardMetrics metrics = new RepoGuardMetrics(
            new SimpleMeterRegistry(),
            new com.repoguard.agent.worker.ReviewExecutionFailureClassifier()
        );
        var apiRegistration = new ApiRequestObservationFilterConfig()
            .apiRequestObservationFilterRegistration(
                metrics,
                new ObservabilityThresholdMonitor(metrics, new ObservabilityThresholdProperties())
            );

        assertThat(traceRegistration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE);
        assertThat(apiRegistration.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 1);
        assertThat(apiRegistration.getUrlPatterns()).containsExactly("/api/v1/*");
    }
}
