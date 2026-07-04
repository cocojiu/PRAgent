package com.repoguard.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NotificationProviderKeyNormalizerTest {

    private final NotificationProviderKeyNormalizer normalizer = new NotificationProviderKeyNormalizer();

    @Test
    void normalizeTreatsMissingProviderAsEmptyKey() {
        assertThat(normalizer.normalize(null)).isEmpty();
    }

    @Test
    void normalizeTrimsAndUppercasesProvider() {
        assertThat(normalizer.normalize("  dingtalk ")).isEqualTo("DINGTALK");
    }
}
