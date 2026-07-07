package com.repoguard.agent.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ObservabilityThresholdMonitorTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final RepoGuardMetrics metrics = new RepoGuardMetrics(
        meterRegistry,
        new com.repoguard.agent.worker.ReviewExecutionFailureClassifier()
    );

    @Test
    void constructorRejectsMissingProperties() {
        assertThatThrownBy(() -> new ObservabilityThresholdMonitor(metrics, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("properties");
    }

    @Test
    void apiRequestUsesPathSpecificDurationAndResponseByteThresholds() {
        ObservabilityThresholdProperties properties = new ObservabilityThresholdProperties();
        properties.setApiDurationMs(2000);
        properties.setApiResponseBytes(524288);
        properties.setApiDurationMsByPath(Map.of("/api/v1/dashboard/summary", 500L));
        properties.setApiResponseBytesByPath(Map.of("/api/v1/dashboard/summary", 8192L));
        ObservabilityThresholdMonitor monitor = new ObservabilityThresholdMonitor(metrics, properties);

        monitor.apiRequest(Duration.ofMillis(700), "/api/v1/dashboard/summary", 9000);

        assertThat(counter(
            "repoguard.observability.threshold.exceeded",
            "signal", "api_duration",
            "subject", "_api_v1_dashboard_summary"
        )).isEqualTo(1.0);
        assertThat(counter(
            "repoguard.observability.threshold.exceeded",
            "signal", "api_response_bytes",
            "subject", "_api_v1_dashboard_summary"
        )).isEqualTo(1.0);
    }

    @Test
    void apiRequestKeepsGlobalThresholdsWhenPathOverrideIsMissingOrInvalid() {
        ObservabilityThresholdProperties properties = new ObservabilityThresholdProperties();
        properties.setApiDurationMs(1000);
        properties.setApiResponseBytes(1000);
        properties.setApiDurationMsByPath(Map.of("/api/v1/dashboard/rules", 0L));
        properties.setApiResponseBytesByPath(Map.of("/api/v1/dashboard/rules", -1L));
        ObservabilityThresholdMonitor monitor = new ObservabilityThresholdMonitor(metrics, properties);

        monitor.apiRequest(Duration.ofMillis(700), "/api/v1/dashboard/rules", 700);
        monitor.apiRequest(Duration.ofMillis(1000), "/api/v1/dashboard/rules", 1000);

        assertThat(counter(
            "repoguard.observability.threshold.exceeded",
            "signal", "api_duration",
            "subject", "_api_v1_dashboard_rules"
        )).isEqualTo(1.0);
        assertThat(counter(
            "repoguard.observability.threshold.exceeded",
            "signal", "api_response_bytes",
            "subject", "_api_v1_dashboard_rules"
        )).isEqualTo(1.0);
    }

    @Test
    void frontendObservationUsesRouteSpecificApiAndLongTaskThresholds() {
        ObservabilityThresholdProperties properties = new ObservabilityThresholdProperties();
        properties.setFrontendApiDurationMs(2000);
        properties.setFrontendLongTaskMs(200);
        properties.setFrontendApiDurationMsByRoute(Map.of("overview", 800L));
        properties.setFrontendLongTaskMsByRoute(Map.of("overview", 120L));
        ObservabilityThresholdMonitor monitor = new ObservabilityThresholdMonitor(metrics, properties);

        monitor.frontendApiRequest(Duration.ofMillis(900), "overview");
        monitor.frontendLongTask(Duration.ofMillis(130), "overview");

        assertThat(counter(
            "repoguard.observability.threshold.exceeded",
            "signal", "frontend_api_duration",
            "subject", "overview"
        )).isEqualTo(1.0);
        assertThat(counter(
            "repoguard.observability.threshold.exceeded",
            "signal", "frontend_long_task",
            "subject", "overview"
        )).isEqualTo(1.0);
    }

    @Test
    void frontendApiThresholdSubjectIncludesRouteOperationAndPath() {
        ObservabilityThresholdProperties properties = new ObservabilityThresholdProperties();
        properties.setFrontendApiDurationMs(2000);
        properties.setFrontendApiDurationMsByRoute(Map.of("task-detail", 800L));
        ObservabilityThresholdMonitor monitor = new ObservabilityThresholdMonitor(metrics, properties);

        monitor.frontendApiRequest(
            Duration.ofMillis(900),
            "task-detail",
            "fetchReviewFindings",
            "/api/v1/reviews/{id}/findings"
        );

        assertThat(counter(
            "repoguard.observability.threshold.exceeded",
            "signal", "frontend_api_duration",
            "subject", "task-detail_fetchreviewfindings_api_v1_reviews_id_findings"
        )).isEqualTo(1.0);
    }

    private double counter(String name, String... tags) {
        return meterRegistry.find(name).tags(tags).counter().count();
    }
}
