package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class DataRetentionPropertiesTest {

    @Test
    void defaultsToThirtyMinuteCleanupLease() {
        DataRetentionProperties properties = new DataRetentionProperties();

        assertThat(properties.getCleanupLeaseMinutes()).isEqualTo(30);
        assertThat(properties.normalizedCleanupLeaseMinutes()).isEqualTo(30);
        assertThat(properties.getCleanupMaxTasksPerRun()).isEqualTo(500);
        assertThat(properties.normalizedCleanupMaxTasksPerRun()).isEqualTo(500);
    }

    @Test
    void nonPositiveCleanupSettingsFallBackToDefaults() {
        DataRetentionProperties properties = new DataRetentionProperties();
        properties.setCleanupLeaseMinutes(0);
        properties.setCleanupMaxTasksPerRun(0);

        assertThat(properties.normalizedCleanupLeaseMinutes()).isEqualTo(30);
        assertThat(properties.normalizedCleanupMaxTasksPerRun()).isEqualTo(500);
    }

    @Test
    void cleanupMaxTasksDoesNotExceedRequestContractLimit() {
        DataRetentionProperties properties = new DataRetentionProperties();
        properties.setCleanupMaxTasksPerRun(6000);

        assertThat(properties.normalizedCleanupMaxTasksPerRun()).isEqualTo(5000);
    }

    @Test
    void applicationYamlDeclaresCleanupBudgets() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();

        assertThat(properties).isNotNull();
        assertThat(defaultValue(properties.getProperty("repoguard.data-retention.cleanup-lease-minutes")))
            .isEqualTo(30);
        assertThat(defaultValue(properties.getProperty("repoguard.data-retention.cleanup-max-tasks-per-run")))
            .isEqualTo(500);
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
