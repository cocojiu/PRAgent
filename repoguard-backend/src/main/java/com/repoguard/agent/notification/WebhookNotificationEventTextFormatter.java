package com.repoguard.agent.notification;

import org.springframework.stereotype.Component;

@Component
class WebhookNotificationEventTextFormatter {

    String format(String eventType) {
        return switch (NotificationEventType.from(eventType)) {
            case REVIEW_COMPLETED -> "审查完成";
            case HUMAN_REVIEW_REQUIRED -> "待人工复核";
            case REVIEW_FAILED -> "审查失败";
            case GITHUB_COMMENT_PUBLISHED -> "GitHub 评论回写";
            case UNKNOWN -> eventType;
        };
    }
}
