package com.repoguard.agent.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class SharedRateLimitMetricsConfigurationTest {

    private static final String API_REQUEST_DURATION = "repoguard.api.request.duration";
    private static final String ACQUIRE_DURATION = "repoguard.security.shared_rate_limit.acquire.duration";
    private static final String DATABASE_DURATION =
        "repoguard.security.shared_rate_limit.database.operation.duration";

    @Test
    void publishesP95P99AndHistogramsForEndpointAndDatabaseRateLimitLatency() {
        Properties properties = applicationProperties();

        for (String meter : new String[] {API_REQUEST_DURATION, ACQUIRE_DURATION, DATABASE_DURATION}) {
            assertThat(properties.getProperty("management.metrics.distribution.percentiles." + meter))
                .isEqualTo("0.95,0.99");
            assertThat(properties.getProperty("management.metrics.distribution.percentiles-histogram." + meter))
                .isEqualTo("true");
        }
    }

    private Properties applicationProperties() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();
        assertThat(properties).isNotNull();
        return properties;
    }
}
