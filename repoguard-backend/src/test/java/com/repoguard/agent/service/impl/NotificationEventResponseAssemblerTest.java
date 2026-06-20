package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.dto.NotificationDeliverySummaryDto;
import com.repoguard.agent.entity.NotificationDeliveryLog;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.notification.NotificationDeliveryStatus;
import com.repoguard.agent.notification.NotificationEventStatus;
import com.repoguard.agent.notification.NotificationEventType;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationEventResponseAssemblerTest {

    private final NotificationEventResponseAssembler assembler = new NotificationEventResponseAssembler();

    @Test
    void assembleEventFormatsEventFieldsAndTimes() {
        var result = assembler.assembleEvent(event(), new NotificationDeliverySummaryDto(List.of("DINGTALK"), 1, 0, "SUCCESS"));

        assertThat(result.id()).isEqualTo(1L);
        assertThat(result.eventType()).isEqualTo(NotificationEventType.REVIEW_COMPLETED.code());
        assertThat(result.status()).isEqualTo(NotificationEventStatus.DELIVERED.code());
        assertThat(result.nextRetryAt()).isEqualTo("2026-06-20 09:55:00");
        assertThat(result.createdAt()).isEqualTo("2026-06-20 09:50:00");
        assertThat(result.updatedAt()).isEqualTo("2026-06-20 09:51:00");
        assertThat(result.deliverySummary().providers()).containsExactly("DINGTALK");
    }

    @Test
    void summarizeDeliveriesNormalizesProvidersCountsFailuresAndUsesLatestStatus() {
        var result = assembler.summarizeDeliveries(List.of(
            delivery(" dingtalk ", NotificationDeliveryStatus.FAILED.code(), LocalDateTime.of(2026, 6, 20, 9, 50)),
            delivery("wecom", NotificationDeliveryStatus.SUCCESS.code(), LocalDateTime.of(2026, 6, 20, 9, 51)),
            delivery("DINGTALK", NotificationDeliveryStatus.SUCCESS.code(), LocalDateTime.of(2026, 6, 20, 9, 49))
        ));

        assertThat(result.providers()).containsExactly("DINGTALK", "WECOM");
        assertThat(result.deliveryCount()).isEqualTo(3);
        assertThat(result.failedDeliveryCount()).isEqualTo(1);
        assertThat(result.latestDeliveryStatus()).isEqualTo(NotificationDeliveryStatus.SUCCESS.code());
    }

    @Test
    void summarizeDeliveriesReturnsEmptySummaryForNoDeliveries() {
        var result = assembler.summarizeDeliveries(List.of());

        assertThat(result.providers()).isEmpty();
        assertThat(result.deliveryCount()).isZero();
        assertThat(result.failedDeliveryCount()).isZero();
        assertThat(result.latestDeliveryStatus()).isNull();
    }

    @Test
    void assembleDeliveryFormatsDeliveryFieldsAndTimes() {
        NotificationDeliveryLog delivery = delivery(
            "DINGTALK",
            NotificationDeliveryStatus.SUCCESS.code(),
            LocalDateTime.of(2026, 6, 20, 9, 52)
        );
        delivery.setFailureReason("timeout");
        delivery.setRequestId("req-1");

        var result = assembler.assembleDelivery(delivery);

        assertThat(result.provider()).isEqualTo("DINGTALK");
        assertThat(result.status()).isEqualTo(NotificationDeliveryStatus.SUCCESS.code());
        assertThat(result.failureReason()).isEqualTo("timeout");
        assertThat(result.requestId()).isEqualTo("req-1");
        assertThat(result.sentAt()).isEqualTo("2026-06-20 09:52:00");
        assertThat(result.createdAt()).isEqualTo("2026-06-20 09:52:00");
    }

    private NotificationEvent event() {
        NotificationEvent event = new NotificationEvent();
        event.setId(1L);
        event.setEventKey(NotificationEventType.REVIEW_COMPLETED.code() + ":100");
        event.setEventType(NotificationEventType.REVIEW_COMPLETED.code());
        event.setTaskId(100L);
        event.setBatchId(200L);
        event.setStatus(NotificationEventStatus.DELIVERED.code());
        event.setRetryCount(2);
        event.setNextRetryAt(LocalDateTime.of(2026, 6, 20, 9, 55));
        event.setLastError("timeout");
        event.setCreatedAt(LocalDateTime.of(2026, 6, 20, 9, 50));
        event.setUpdatedAt(LocalDateTime.of(2026, 6, 20, 9, 51));
        return event;
    }

    private NotificationDeliveryLog delivery(String provider, String status, LocalDateTime createdAt) {
        NotificationDeliveryLog delivery = new NotificationDeliveryLog();
        delivery.setId(11L);
        delivery.setEventId(1L);
        delivery.setBindingId(7L);
        delivery.setTaskId(100L);
        delivery.setProvider(provider);
        delivery.setStatus(status);
        delivery.setAttemptCount(1);
        delivery.setSentAt(createdAt);
        delivery.setCreatedAt(createdAt);
        return delivery;
    }
}
