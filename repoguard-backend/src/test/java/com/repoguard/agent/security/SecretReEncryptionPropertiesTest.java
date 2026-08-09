package com.repoguard.agent.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class SecretReEncryptionPropertiesTest {

    @Test
    void defaultsKeepBatchesAndLeasesBounded() {
        SecretReEncryptionProperties properties = new SecretReEncryptionProperties();

        assertThat(properties.getBatchSize()).isEqualTo(100);
        assertThat(properties.getLeaseSeconds()).isEqualTo(120);
        assertThat(properties.getRetryDelaySeconds()).isEqualTo(30);
        assertThat(properties.getMaxAttempts()).isEqualTo(5);
        assertThat(properties.getPollIntervalMs()).isEqualTo(1000);
    }

    @Test
    void applicationYamlDeclaresAllWorkerBudgets() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();

        assertThat(properties).isNotNull();
        assertThat(defaultValue(properties, "batch-size")).isEqualTo(100);
        assertThat(defaultValue(properties, "lease-seconds")).isEqualTo(120);
        assertThat(defaultValue(properties, "retry-delay-seconds")).isEqualTo(30);
        assertThat(defaultValue(properties, "max-attempts")).isEqualTo(5);
        assertThat(defaultValue(properties, "poll-interval-ms")).isEqualTo(1000);
    }

    private static long defaultValue(Properties properties, String name) {
        String placeholder = properties.getProperty("repoguard.security.re-encryption." + name);
        int separator = placeholder.lastIndexOf(':');
        int end = placeholder.lastIndexOf('}');
        assertThat(separator).as("placeholder separator for " + name).isGreaterThan(0);
        assertThat(end).as("placeholder end for " + name).isGreaterThan(separator);
        return Long.parseLong(placeholder.substring(separator + 1, end));
    }
}
