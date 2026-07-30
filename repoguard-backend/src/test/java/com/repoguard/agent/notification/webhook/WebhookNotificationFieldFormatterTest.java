package com.repoguard.agent.notification.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WebhookNotificationFieldFormatterTest {

    private final WebhookNotificationFieldFormatter formatter = new WebhookNotificationFieldFormatter();

    @Test
    void textUsesDashForBlankValues() {
        assertThat(formatter.text(null)).isEqualTo("-");
        assertThat(formatter.text("")).isEqualTo("-");
        assertThat(formatter.text("  ")).isEqualTo("-");
    }

    @Test
    void textTrimsAndFlattensMultilineValues() {
        assertThat(formatter.text(" api\r\nservice ")).isEqualTo("api  service");
    }

    @Test
    void countUsesZeroWhenMissing() {
        assertThat(formatter.count(null)).isZero();
        assertThat(formatter.count(3)).isEqualTo(3);
    }
}
