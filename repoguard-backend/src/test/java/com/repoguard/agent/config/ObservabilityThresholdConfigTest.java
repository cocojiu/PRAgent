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
    private static final List<String> REVIEW_PERFORMANCE_PATHS = List.of(
        "/api/v1/reviews",
        "/api/v1/reviews/repositories",
        "/api/v1/reviews/{id}",
        "/api/v1/reviews/{id}/findings",
        "/api/v1/reviews/{id}/changed-files",
        "/api/v1/reviews/{id}/missing-tests",
        "/api/v1/reviews/{id}/timeline",
        "/api/v1/reviews/{id}/status",
        "/api/v1/reviews/{id}/github-comments/preview",
        "/api/v1/reviews/{id}/github-comments/publications"
    );

    @Test
    void reviewDetailEndpointsDeclareDurationAndResponseSizeBudgets() {
        Properties properties = applicationProperties();

        for (String path : REVIEW_PERFORMANCE_PATHS) {
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
