package com.repoguard.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WebhookNotificationContentBuilderTest {

    private final WebhookNotificationContentBuilder builder = new WebhookNotificationContentBuilder(
        new WebhookNotificationEventTextFormatter()
    );

    @Test
    void buildsReviewNotificationTitleAndMarkdown() {
        WebhookNotificationContent content = builder.reviewContent(message());

        assertThat(content.title()).isEqualTo("RepoGuard 待人工复核 - api PR #512");
        assertThat(content.markdown()).contains(
            "### RepoGuard 审查通知",
            "- 事件：待人工复核",
            "- 仓库：octocat/api",
            "- PR：#512 Fix auth bypass",
            "- 状态：PENDING_HUMAN_REVIEW",
            "- 风险：HIGH",
            "- 问题数：3",
            "- 评论回写：成功 1，失败 2，跳过 0",
            "[查看详情](https://repoguard.local/reviews/42)"
        );
    }

    @Test
    void sanitizesBlankAndMultilineFields() {
        WebhookNotificationContent content = builder.reviewContent(new NotificationMessage(
            "custom_event",
            42L,
            null,
            null,
            "api\nservice",
            null,
            "Fix\r\nauth",
            "",
            null,
            null,
            null,
            null,
            null,
            ""
        ));

        assertThat(content.title()).isEqualTo("RepoGuard custom_event - api service PR #-");
        assertThat(content.markdown()).contains(
            "- 事件：custom_event",
            "- 仓库：-/api service",
            "- PR：#- Fix  auth",
            "- 状态：-",
            "- 风险：-",
            "- 问题数：0",
            "- 评论回写：成功 0，失败 0，跳过 0",
            "[查看详情](-)"
        );
    }

    @Test
    void buildsConnectionTestContent() {
        WebhookNotificationContent content = builder.testContent();

        assertThat(content.title()).isEqualTo("RepoGuard 通知测试");
        assertThat(content.markdown()).contains("### RepoGuard 通知测试", "这是一条连接测试消息。", "时间：");
    }

    private NotificationMessage message() {
        return new NotificationMessage(
            "HUMAN_REVIEW_REQUIRED",
            42L,
            7L,
            "octocat",
            "api",
            512,
            "Fix auth bypass",
            "PENDING_HUMAN_REVIEW",
            "HIGH",
            3,
            1,
            2,
            0,
            "https://repoguard.local/reviews/42"
        );
    }
}
