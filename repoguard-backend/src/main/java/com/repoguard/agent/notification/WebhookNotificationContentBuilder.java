package com.repoguard.agent.notification;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class WebhookNotificationContentBuilder {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    WebhookNotificationContent reviewContent(NotificationMessage message) {
        return new WebhookNotificationContent(title(message), markdown(message));
    }

    WebhookNotificationContent testContent() {
        return new WebhookNotificationContent(
            "RepoGuard 通知测试",
            "### RepoGuard 通知测试\n\n这是一条连接测试消息。\n\n时间：" + DATE_TIME_FORMATTER.format(LocalDateTime.now())
        );
    }

    private String markdown(NotificationMessage message) {
        return """
            ### RepoGuard 审查通知

            - 事件：%s
            - 仓库：%s/%s
            - PR：#%s %s
            - 状态：%s
            - 风险：%s
            - 问题数：%s
            - 评论回写：成功 %s，失败 %s，跳过 %s

            [查看详情](%s)
            """.formatted(
            eventText(message.eventType()),
            safe(message.organization()),
            safe(message.repository()),
            message.prNumber() == null ? "-" : message.prNumber(),
            safe(message.title()),
            safe(message.status()),
            safe(message.riskLevel()),
            message.findingCount() == null ? 0 : message.findingCount(),
            message.commentSucceededCount() == null ? 0 : message.commentSucceededCount(),
            message.commentFailedCount() == null ? 0 : message.commentFailedCount(),
            message.commentSkippedCount() == null ? 0 : message.commentSkippedCount(),
            safe(message.detailUrl())
        );
    }

    private String title(NotificationMessage message) {
        return "RepoGuard " + eventText(message.eventType()) + " - " + safe(message.repository()) + " PR #" + (message.prNumber() == null ? "-" : message.prNumber());
    }

    private String eventText(String eventType) {
        return switch (NotificationEventType.from(eventType)) {
            case REVIEW_COMPLETED -> "审查完成";
            case HUMAN_REVIEW_REQUIRED -> "待人工复核";
            case REVIEW_FAILED -> "审查失败";
            case GITHUB_COMMENT_PUBLISHED -> "GitHub 评论回写";
            case UNKNOWN -> eventType;
        };
    }

    private String safe(String value) {
        if (!StringUtils.hasText(value)) {
            return "-";
        }
        return value.replace("\r", " ").replace("\n", " ").trim();
    }
}
