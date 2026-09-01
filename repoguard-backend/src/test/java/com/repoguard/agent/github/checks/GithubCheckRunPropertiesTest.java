package com.repoguard.agent.github.checks;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GithubCheckRunPropertiesTest {

    @Test
    void defaultsAreBoundedForSafeGithubDispatch() {
        GithubCheckRunProperties properties = new GithubCheckRunProperties();

        assertThat(properties.getName()).isEqualTo("RepoGuard PR Review");
        assertThat(properties.getRecoveryIntervalMs()).isEqualTo(5000);
        assertThat(properties.getRecoveryBatchSize()).isEqualTo(20);
        assertThat(properties.getClaimLeaseSeconds()).isEqualTo(120);
        assertThat(properties.getRetryBaseSeconds()).isEqualTo(10);
        assertThat(properties.getRetryMaxSeconds()).isEqualTo(900);
        assertThat(properties.getAnnotationLimit()).isEqualTo(50);
        properties.validateForProfiles(new String[] {"prod"});
    }

    @Test
    void rejectsInvalidLimitsAndProductionBlankName() {
        GithubCheckRunProperties invalidRecovery = new GithubCheckRunProperties();
        invalidRecovery.setRecoveryIntervalMs(0);
        assertThatThrownBy(() -> invalidRecovery.validateForProfiles(new String[] {"test"}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("recovery settings must be positive");

        GithubCheckRunProperties invalidRetry = new GithubCheckRunProperties();
        invalidRetry.setRetryMaxSeconds(1);
        assertThatThrownBy(() -> invalidRetry.validateForProfiles(new String[] {"test"}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("retry settings are invalid");

        GithubCheckRunProperties invalidName = new GithubCheckRunProperties();
        invalidName.setEnabled(true);
        invalidName.setName(" ");
        assertThatThrownBy(() -> invalidName.validateForProfiles(new String[] {"prod"}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("name must contain");

        GithubCheckRunProperties invalidAnnotations = new GithubCheckRunProperties();
        invalidAnnotations.setAnnotationLimit(51);
        assertThatThrownBy(() -> invalidAnnotations.validateForProfiles(new String[] {"test"}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("annotation-limit");
    }

    @Test
    void bindsAllDispatchSettingsAndRejectsEveryNonPositiveLimit() {
        GithubCheckRunProperties properties = new GithubCheckRunProperties();
        properties.setEnabled(true);
        properties.setName("  Checks  ");
        properties.setRecoveryIntervalMs(2500);
        properties.setRecoveryBatchSize(7);
        properties.setClaimLeaseSeconds(45);
        properties.setRetryBaseSeconds(3);
        properties.setRetryMaxSeconds(30);
        properties.setAnnotationLimit(4);

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getName()).isEqualTo("  Checks  ");
        assertThat(properties.getRecoveryIntervalMs()).isEqualTo(2500);
        assertThat(properties.getRecoveryBatchSize()).isEqualTo(7);
        assertThat(properties.getClaimLeaseSeconds()).isEqualTo(45);
        assertThat(properties.getRetryBaseSeconds()).isEqualTo(3);
        assertThat(properties.getRetryMaxSeconds()).isEqualTo(30);
        assertThat(properties.getAnnotationLimit()).isEqualTo(4);
        properties.validateForProfiles(new String[] {"prod"});

        GithubCheckRunProperties invalidBatch = new GithubCheckRunProperties();
        invalidBatch.setRecoveryBatchSize(0);
        assertThatThrownBy(() -> invalidBatch.validateForProfiles(new String[] {"test"}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("recovery settings");

        GithubCheckRunProperties invalidLease = new GithubCheckRunProperties();
        invalidLease.setClaimLeaseSeconds(0);
        assertThatThrownBy(() -> invalidLease.validateForProfiles(new String[] {"test"}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("recovery settings");

        GithubCheckRunProperties invalidAnnotation = new GithubCheckRunProperties();
        invalidAnnotation.setAnnotationLimit(0);
        assertThatThrownBy(() -> invalidAnnotation.validateForProfiles(new String[] {"test"}))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("annotation-limit");
    }
}
