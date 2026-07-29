package com.repoguard.agent.notification.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.notification.NotificationEventType;
import org.junit.jupiter.api.Test;

class NotificationEventKeyFactoryTest {

    private final NotificationEventKeyFactory factory = new NotificationEventKeyFactory();

    @Test
    void createUsesEventTypeAndTaskWhenBatchIsMissing() {
        assertThat(factory.create(NotificationEventType.REVIEW_COMPLETED.code(), 42L, null))
            .isEqualTo("REVIEW_COMPLETED:42");
    }

    @Test
    void createIncludesBatchWhenPresent() {
        assertThat(factory.create(NotificationEventType.GITHUB_COMMENT_PUBLISHED.code(), 42L, 99L))
            .isEqualTo("GITHUB_COMMENT_PUBLISHED:42:99");
    }
}
