package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

class GithubDiffBudgetPropertiesTest {

    @Test
    void defaultsBoundPagesFilesBytesPatchAndElapsedTime() {
        GithubDiffBudgetProperties properties = new GithubDiffBudgetProperties();

        assertThat(properties.getMaxPages()).isEqualTo(10);
        assertThat(properties.getMaxFiles()).isEqualTo(1_000);
        assertThat(properties.getMaxTotalBytes()).isEqualTo(33_554_432);
        assertThat(properties.getMaxPatchBytes()).isEqualTo(524_288);
        assertThat(properties.getTotalTimeoutMs()).isEqualTo(90_000);
    }

    @Test
    void applicationYamlExposesEveryBudgetAsRuntimeConfiguration() {
        YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("application.yml"));
        Properties properties = yaml.getObject();

        assertThat(properties).isNotNull();
        assertThat(properties.getProperty("app.github.diff-budget.max-pages"))
            .isEqualTo("${REPOGUARD_GITHUB_DIFF_MAX_PAGES:10}");
        assertThat(properties.getProperty("app.github.diff-budget.max-files"))
            .isEqualTo("${REPOGUARD_GITHUB_DIFF_MAX_FILES:1000}");
        assertThat(properties.getProperty("app.github.diff-budget.max-total-bytes"))
            .isEqualTo("${REPOGUARD_GITHUB_DIFF_MAX_TOTAL_BYTES:33554432}");
        assertThat(properties.getProperty("app.github.diff-budget.max-patch-bytes"))
            .isEqualTo("${REPOGUARD_GITHUB_DIFF_MAX_PATCH_BYTES:524288}");
        assertThat(properties.getProperty("app.github.diff-budget.total-timeout-ms"))
            .isEqualTo("${REPOGUARD_GITHUB_DIFF_TOTAL_TIMEOUT_MS:90000}");
    }
}
