package com.repoguard.agent.notification.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WebhookNotificationEventTextFormatterTest {

    private final WebhookNotificationEventTextFormatter formatter = new WebhookNotificationEventTextFormatter();

    @Test
    void formatsKnownEventsForWebhookContent() {
        assertThat(formatter.format("REVIEW_COMPLETED")).isEqualTo("审查完成");
        assertThat(formatter.format("HUMAN_REVIEW_REQUIRED")).isEqualTo("待人工复核");
        assertThat(formatter.format("REVIEW_FAILED")).isEqualTo("审查失败");
        assertThat(formatter.format("GITHUB_COMMENT_PUBLISHED")).isEqualTo("GitHub 评论回写");
        assertThat(formatter.format("MODEL_RELEASE_ALERT")).isEqualTo("模型发布告警");
    }

    @Test
    void keepsUnknownEventCode() {
        assertThat(formatter.format("custom_event")).isEqualTo("custom_event");
    }
}
