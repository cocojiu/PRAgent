package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class ObservabilityThresholdConfigTest {

    private static final String DURATION_PREFIX =
        "repoguard.observability.thresholds.api-duration-ms-by-path";
    private static final String RESPONSE_BYTES_PREFIX =
        "repoguard.observability.thresholds.api-response-bytes-by-path";
    private static final String RETRY_ATTEMPT_PREFIX =
        "repoguard.observability.thresholds.external-call-retry-attempt-by-system";
    private static final List<String> API_PERFORMANCE_PATHS = List.of(
        "/api/v1/dashboard/overview",
        "/api/v1/dashboard/summary",
        "/api/v1/dashboard/review-trend",
        "/api/v1/dashboard/risk-distribution",
        "/api/v1/dashboard/rules",
        "/api/v1/dashboard/high-risk-reviews",
        "/api/v1/dashboard/llm-quality",
        "/api/v1/reviews",
        "/api/v1/reviews/repositories",
        "/api/v1/reviews/{id}",
        "/api/v1/reviews/{id}/findings",
        "/api/v1/reviews/{id}/changed-files",
        "/api/v1/reviews/{id}/missing-tests",
        "/api/v1/reviews/{id}/timeline",
        "/api/v1/reviews/{id}/status",
        "/api/v1/reviews/{id}/github-comments/preview",
        "/api/v1/reviews/{id}/github-comments/publications",
        "/api/v1/config/notification-bindings",
        "/api/v1/config/notification-bindings/{id}/test",
        "/api/v1/notification-events",
        "/api/v1/notification-events/{id}/retry",
        "/api/v1/notification-deliveries",
        "/api/v1/notifications"
    );

    @Test
    void keyApiEndpointsDeclareDurationAndResponseSizeBudgets() {
        Properties properties = applicationProperties();

        for (String path : API_PERFORMANCE_PATHS) {
            String durationBudget = properties.getProperty(mapKey(DURATION_PREFIX, path));
            String responseBytesBudget = properties.getProperty(mapKey(RESPONSE_BYTES_PREFIX, path));

            assertThat(durationBudget)
                .as(path + " duration threshold")
                .isNotBlank();
            assertThat(defaultValue(durationBudget))
                .as(path + " duration default")
                .isPositive();
            assertThat(responseBytesBudget)
                .as(path + " response bytes threshold")
                .isNotBlank();
            assertThat(defaultValue(responseBytesBudget))
                .as(path + " response bytes default")
                .isPositive();
        }
    }

    @Test
    void externalCallRetryThresholdsDeclareProviderBudgets() {
        Properties properties = applicationProperties();

        assertThat(defaultValue(properties.getProperty("repoguard.observability.thresholds.external-call-retry-attempt")))
            .isPositive();
        assertThat(defaultValue(properties.getProperty(mapKey(RETRY_ATTEMPT_PREFIX, "github"))))
            .isPositive();
        assertThat(defaultValue(properties.getProperty(mapKey(RETRY_ATTEMPT_PREFIX, "llm"))))
            .isPositive();
    }

    @Test
    void dataRetentionCleanupFailureThresholdDeclaresBudget() {
        Properties properties = applicationProperties();

        assertThat(defaultValue(properties.getProperty(
            "repoguard.observability.thresholds.data-retention-cleanup-failures"
        ))).isPositive();
    }

    private Properties applicationProperties() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();
        assertThat(properties).isNotNull();
        return properties;
    }

    private static String mapKey(String prefix, String path) {
        return prefix + "[" + path + "]";
    }

    private static long defaultValue(String placeholder) {
        int separator = placeholder.lastIndexOf(':');
        int end = placeholder.lastIndexOf('}');
        assertThat(separator)
            .as("placeholder separator for " + placeholder)
            .isGreaterThan(0);
        assertThat(end)
            .as("placeholder end for " + placeholder)
            .isGreaterThan(separator);
        return Long.parseLong(placeholder.substring(separator + 1, end));
    }
}
