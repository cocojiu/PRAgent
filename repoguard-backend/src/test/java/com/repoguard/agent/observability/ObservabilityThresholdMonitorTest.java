package com.repoguard.agent.observability;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repoguard.agent.external.ExternalCallException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ObservabilityThresholdMonitorTest {

    private final SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
    private final RepoGuardMetrics metrics = RepoGuardMetrics.forTesting(
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
    void apiThresholdSubjectIncludesMethodWhenProvided() {
        ObservabilityThresholdProperties properties = new ObservabilityThresholdProperties();
        properties.setApiDurationMs(500);
        properties.setApiDurationMsByPath(Map.of("/api/v1/reviews/{id}", 200L));
        ObservabilityThresholdMonitor monitor = new ObservabilityThresholdMonitor(metrics, properties);

        monitor.apiRequest(Duration.ofMillis(250), "GET", "/api/v1/reviews/{id}", 128);

        assertThat(counter(
            "repoguard.observability.threshold.exceeded",
            "signal", "api_duration",
            "subject", "get_api_v1_reviews_id_"
        )).isEqualTo(1.0);
    }

    @Test
    void sqlThresholdSubjectIncludesCommandAndResultWhenProvided() {
        ObservabilityThresholdProperties properties = new ObservabilityThresholdProperties();
        properties.setSqlRows(2);
        ObservabilityThresholdMonitor monitor = new ObservabilityThresholdMonitor(metrics, properties);

        monitor.sqlQuery(Duration.ofMillis(10), "DashboardMapper.selectMetricStat", "SELECT", "success", 3);

        assertThat(counter(
            "repoguard.observability.threshold.exceeded",
            "signal", "sql_rows",
            "subject", "dashboardmapper.selectmetricstat_select_success"
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

    @Test
    void externalCallRetryUsesSystemSpecificAttemptThreshold() {
        ObservabilityThresholdProperties properties = new ObservabilityThresholdProperties();
        properties.setExternalCallRetryAttempt(3);
        properties.setExternalCallRetryAttemptBySystem(Map.of("github", 2L));
        ObservabilityThresholdMonitor monitor = new ObservabilityThresholdMonitor(metrics, properties);

        monitor.externalCallRetry(externalCall("GitHub", "github_service_unavailable", 502), 1);
        monitor.externalCallRetry(externalCall("GitHub", "github_service_unavailable", 502), 2);

        assertThat(counter(
            "repoguard.observability.threshold.exceeded",
            "signal", "external_call_retry_attempt",
            "subject", "github_github_service_unavailable_502"
        )).isEqualTo(1.0);
    }

    @Test
    void externalCallRetryFallsBackToGlobalAttemptThreshold() {
        ObservabilityThresholdProperties properties = new ObservabilityThresholdProperties();
        properties.setExternalCallRetryAttempt(2);
        ObservabilityThresholdMonitor monitor = new ObservabilityThresholdMonitor(metrics, properties);

        monitor.externalCallRetry(externalCall("LLM", "llm_timeout", null), 2);

        assertThat(counter(
            "repoguard.observability.threshold.exceeded",
            "signal", "external_call_retry_attempt",
            "subject", "llm_llm_timeout_none"
        )).isEqualTo(1.0);
    }

    @Test
    void dataRetentionCleanupFailureUsesUnifiedThresholdSignal() {
        ObservabilityThresholdMonitor monitor = new ObservabilityThresholdMonitor(
            metrics,
            new ObservabilityThresholdProperties()
        );

        monitor.dataRetentionCleanupFailure(true, "database_error");

        assertThat(counter(
            "repoguard.observability.threshold.exceeded",
            "signal", "data_retention_cleanup_failed",
            "subject", "execute_database_error"
        )).isEqualTo(1.0);
    }

    @Test
    void dataRetentionCleanupFailureCanDisableSingleFailureThresholdSignal() {
        ObservabilityThresholdProperties properties = new ObservabilityThresholdProperties();
        properties.setDataRetentionCleanupFailures(2);
        ObservabilityThresholdMonitor monitor = new ObservabilityThresholdMonitor(metrics, properties);

        monitor.dataRetentionCleanupFailure(false, "bad_request");

        assertThat(meterRegistry.find("repoguard.observability.threshold.exceeded")
            .tag("signal", "data_retention_cleanup_failed")
            .tag("subject", "dry_run_bad_request")
            .counter()).isNull();
    }

    private double counter(String name, String... tags) {
        return meterRegistry.find(name).tags(tags).counter().count();
    }

    private ExternalCallException externalCall(String system, String category, Integer statusCode) {
        return new ExternalCallException(
            system,
            category,
            true,
            statusCode,
            "retry",
            new RuntimeException("retry")
        );
    }
}
