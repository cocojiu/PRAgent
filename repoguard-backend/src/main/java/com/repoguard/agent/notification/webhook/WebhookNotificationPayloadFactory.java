package com.repoguard.agent.notification.webhook;

import java.util.Map;
import org.springframework.stereotype.Component;

@Component
class WebhookNotificationPayloadFactory {

    Object dingTalkMarkdown(String title, String markdown) {
        return Map.of(
            "msgtype", "markdown",
            "markdown", Map.of("title", title, "text", markdown)
        );
    }

    Object weComMarkdown(String markdown) {
        return Map.of(
            "msgtype", "markdown",
            "markdown", Map.of("content", markdown)
        );
    }

    Object feishuText(String markdown) {
        return Map.of("msg_type", "text", "content", Map.of("text", markdown));
    }

    Object slackText(String title, String markdown) {
        return Map.of("text", "*" + title + "*\n" + markdown);
    }

    Object emailMessage(String title, String markdown) {
        return Map.of("subject", title, "text", markdown, "format", "markdown");
    }
}
