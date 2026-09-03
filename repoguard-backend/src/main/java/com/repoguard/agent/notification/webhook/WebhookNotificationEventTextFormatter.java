package com.repoguard.agent.notification.webhook;

import com.repoguard.agent.notification.NotificationEventType;
import org.springframework.stereotype.Component;

@Component
class WebhookNotificationEventTextFormatter {

    String format(String eventType) {
        return switch (NotificationEventType.from(eventType)) {
            case REVIEW_COMPLETED -> "审查完成";
            case HUMAN_REVIEW_REQUIRED -> "待人工复核";
            case REVIEW_FAILED -> "审查失败";
            case GITHUB_COMMENT_PUBLISHED -> "GitHub 评论回写";
            case MODEL_RELEASE_ALERT -> "模型发布告警";
            case UNKNOWN -> eventType;
        };
    }
}
