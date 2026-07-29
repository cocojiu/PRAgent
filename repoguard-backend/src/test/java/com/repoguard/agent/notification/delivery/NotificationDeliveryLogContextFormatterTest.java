package com.repoguard.agent.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NotificationDeliveryLogContextFormatterTest {

    private final NotificationDeliveryLogContextFormatter formatter = new NotificationDeliveryLogContextFormatter();

    @Test
    void safePartNormalizesBlankValues() {
        assertThat(formatter.safePart(null)).isEqualTo("<unknown>");
        assertThat(formatter.safePart("  ")).isEqualTo("<unknown>");
    }

    @Test
    void safePartTrimsPresentValues() {
        assertThat(formatter.safePart(" REVIEW_COMPLETED ")).isEqualTo("REVIEW_COMPLETED");
    }
}
