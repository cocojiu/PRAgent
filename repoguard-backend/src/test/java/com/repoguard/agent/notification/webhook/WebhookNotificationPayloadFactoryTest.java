package com.repoguard.agent.notification.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import org.junit.jupiter.api.Test;

class WebhookNotificationPayloadFactoryTest {

    private final WebhookNotificationPayloadFactory factory = new WebhookNotificationPayloadFactory();

    @Test
    void buildsDingTalkMarkdownPayload() {
        Object payload = factory.dingTalkMarkdown("Review done", "### body");

        assertThat(payload).isEqualTo(Map.of(
            "msgtype", "markdown",
            "markdown", Map.of("title", "Review done", "text", "### body")
        ));
    }

    @Test
    void buildsWeComMarkdownPayload() {
        Object payload = factory.weComMarkdown("### body");

        assertThat(payload).isEqualTo(Map.of(
            "msgtype", "markdown",
            "markdown", Map.of("content", "### body")
        ));
    }
}
