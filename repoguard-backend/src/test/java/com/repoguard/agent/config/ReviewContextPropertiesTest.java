package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class ReviewContextPropertiesTest {

    @Test
    void defaultsBoundFileCountBytesElapsedTimeAndCache() {
        ReviewContextProperties properties = new ReviewContextProperties();

        assertThat(properties.getMaxFiles()).isEqualTo(100);
        assertThat(properties.getMaxFileBytes()).isEqualTo(524_288);
        assertThat(properties.getMaxTotalBytes()).isEqualTo(8_388_608);
        assertThat(properties.getTotalTimeoutMs()).isEqualTo(15_000);
        assertThat(properties.getCacheMaximumSize()).isEqualTo(2_000);
        assertThat(properties.getCacheMaximumBytes()).isEqualTo(67_108_864);
        assertThat(properties.getCacheTtlSeconds()).isEqualTo(600);
        assertThat(properties.getExcludedPathPatterns()).isNotEmpty();
        assertThat(properties.getNonProductionPathPatterns()).isNotEmpty();
    }

    @Test
    void applicationYamlExposesEveryContextBudgetAsRuntimeConfiguration() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();

        assertThat(properties).isNotNull();
        assertThat(properties.getProperty("repoguard.review.changed-file-context.max-files"))
            .isEqualTo("${REPOGUARD_REVIEW_CONTEXT_MAX_FILES:100}");
        assertThat(properties.getProperty("repoguard.review.changed-file-context.max-file-bytes"))
            .isEqualTo("${REPOGUARD_REVIEW_CONTEXT_MAX_FILE_BYTES:524288}");
        assertThat(properties.getProperty("repoguard.review.changed-file-context.max-total-bytes"))
            .isEqualTo("${REPOGUARD_REVIEW_CONTEXT_MAX_TOTAL_BYTES:8388608}");
        assertThat(properties.getProperty("repoguard.review.changed-file-context.total-timeout-ms"))
            .isEqualTo("${REPOGUARD_REVIEW_CONTEXT_TOTAL_TIMEOUT_MS:15000}");
        assertThat(properties.getProperty("repoguard.review.changed-file-context.cache-maximum-size"))
            .isEqualTo("${REPOGUARD_REVIEW_CONTEXT_CACHE_MAXIMUM_SIZE:2000}");
        assertThat(properties.getProperty("repoguard.review.changed-file-context.cache-maximum-bytes"))
            .isEqualTo("${REPOGUARD_REVIEW_CONTEXT_CACHE_MAXIMUM_BYTES:67108864}");
        assertThat(properties.getProperty("repoguard.review.changed-file-context.cache-ttl-seconds"))
            .isEqualTo("${REPOGUARD_REVIEW_CONTEXT_CACHE_TTL_SECONDS:600}");
    }
}
