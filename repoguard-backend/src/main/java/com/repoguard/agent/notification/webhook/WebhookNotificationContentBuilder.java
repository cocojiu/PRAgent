package com.repoguard.agent.notification.webhook;

import com.repoguard.agent.notification.NotificationMessage;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class WebhookNotificationContentBuilder {

    private static final DateTimeFormatter DATE_TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final WebhookNotificationEventTextFormatter eventTextFormatter;
    private final WebhookNotificationFieldFormatter fieldFormatter;

    WebhookNotificationContentBuilder(
        WebhookNotificationEventTextFormatter eventTextFormatter,
        WebhookNotificationFieldFormatter fieldFormatter
    ) {
        this.eventTextFormatter = Objects.requireNonNull(eventTextFormatter, "eventTextFormatter");
        this.fieldFormatter = Objects.requireNonNull(fieldFormatter, "fieldFormatter");
    }

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
            text(message.organization()),
            text(message.repository()),
            message.prNumber() == null ? "-" : message.prNumber(),
            text(message.title()),
            text(message.status()),
            text(message.riskLevel()),
            count(message.findingCount()),
            count(message.commentSucceededCount()),
            count(message.commentFailedCount()),
            count(message.commentSkippedCount()),
            text(message.detailUrl())
        );
    }

    private String title(NotificationMessage message) {
        return "RepoGuard " + eventText(message.eventType()) + " - " + text(message.repository()) + " PR #" + (message.prNumber() == null ? "-" : message.prNumber());
    }

    private String eventText(String eventType) {
        return eventTextFormatter.format(eventType);
    }

    private String text(String value) {
        return fieldFormatter.text(value);
    }

    private int count(Integer value) {
        return fieldFormatter.count(value);
    }
}
