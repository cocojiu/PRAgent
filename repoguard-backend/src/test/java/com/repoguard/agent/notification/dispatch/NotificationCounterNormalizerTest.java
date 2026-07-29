package com.repoguard.agent.notification.dispatch;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NotificationCounterNormalizerTest {

    private final NotificationCounterNormalizer normalizer = new NotificationCounterNormalizer();

    @Test
    void safeTreatsNullAsZero() {
        assertThat(normalizer.safe(null)).isZero();
    }

    @Test
    void safeKeepsProvidedValue() {
        assertThat(normalizer.safe(7)).isEqualTo(7);
    }
}
