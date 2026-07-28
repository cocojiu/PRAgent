package com.repoguard.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NotificationStatusTest {

    @Test
    void bindingStatusNormalizesStoredCodes() {
        assertThat(NotificationBindingStatus.from("configured")).isEqualTo(NotificationBindingStatus.CONFIGURED);
        assertThat(NotificationBindingStatus.from(" connected ")).isEqualTo(NotificationBindingStatus.CONNECTED);
        assertThat(NotificationBindingStatus.from("deleted")).isEqualTo(NotificationBindingStatus.DELETED);
        assertThat(NotificationBindingStatus.from(null)).isEqualTo(NotificationBindingStatus.UNKNOWN);
        assertThat(NotificationBindingStatus.from("archived")).isEqualTo(NotificationBindingStatus.UNKNOWN);
    }

    @Test
    void eventStatusNormalizesStoredCodes() {
        assertThat(NotificationEventStatus.from("pending")).isEqualTo(NotificationEventStatus.PENDING);
        assertThat(NotificationEventStatus.from("publishing")).isEqualTo(NotificationEventStatus.PUBLISHING);
        assertThat(NotificationEventStatus.from("delivery_failed")).isEqualTo(NotificationEventStatus.DELIVERY_FAILED);
        assertThat(NotificationEventStatus.from(" publish_failed ")).isEqualTo(NotificationEventStatus.PUBLISH_FAILED);
        assertThat(NotificationEventStatus.from(null)).isEqualTo(NotificationEventStatus.UNKNOWN);
        assertThat(NotificationEventStatus.from("ignored")).isEqualTo(NotificationEventStatus.UNKNOWN);
    }

    @Test
    void deliveryStatusNormalizesStoredCodes() {
        assertThat(NotificationDeliveryStatus.from("success")).isEqualTo(NotificationDeliveryStatus.SUCCESS);
        assertThat(NotificationDeliveryStatus.from(" failed ")).isEqualTo(NotificationDeliveryStatus.FAILED);
        assertThat(NotificationDeliveryStatus.from(null)).isEqualTo(NotificationDeliveryStatus.UNKNOWN);
        assertThat(NotificationDeliveryStatus.from("skipped")).isEqualTo(NotificationDeliveryStatus.UNKNOWN);
    }

    @Test
    void eventTypeNormalizesStoredCodes() {
        assertThat(NotificationEventType.from("review_completed")).isEqualTo(NotificationEventType.REVIEW_COMPLETED);
        assertThat(NotificationEventType.from(" human_review_required ")).isEqualTo(NotificationEventType.HUMAN_REVIEW_REQUIRED);
        assertThat(NotificationEventType.from("github_comment_published")).isEqualTo(NotificationEventType.GITHUB_COMMENT_PUBLISHED);
        assertThat(NotificationEventType.from(null)).isEqualTo(NotificationEventType.UNKNOWN);
        assertThat(NotificationEventType.from("custom_event")).isEqualTo(NotificationEventType.UNKNOWN);
    }
}
