package com.repoguard.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.mapper.NotificationEventMapper;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NotificationDeliveryEventStateUpdaterTest {

    private final NotificationEventMapper eventMapper = org.mockito.Mockito.mock(NotificationEventMapper.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-06-18T10:30:00Z"), ZoneId.of("UTC"));
    private final NotificationDeliveryEventStateUpdater updater = new NotificationDeliveryEventStateUpdater(eventMapper, clock);

    @Test
    void claimForDeliveryUsesPublishedStatusAndStoresOwnership() {
        when(eventMapper.update(any(UpdateWrapper.class))).thenReturn(1);
        NotificationDeliveryClaim claim = new NotificationDeliveryClaim(
            LocalDateTime.of(2026, 6, 18, 10, 30),
            "worker-1"
        );

        assertThat(updater.claimForDelivery(event(), claim)).isTrue();

        UpdateWrapper<NotificationEvent> wrapper = capturedUpdate();
        assertThat(wrapper.getSqlSegment()).contains("status", "delivery_claimed_at", "IS NULL");
        assertThat(wrapper.getSqlSet()).contains("status", "delivery_claimed_at", "delivery_claimed_by", "updated_at");
        assertThat(wrapper.getParamNameValuePairs())
            .containsValue(NotificationEventStatus.DELIVERING.code())
            .containsValue("worker-1")
            .containsValue(LocalDateTime.of(2026, 6, 18, 10, 30));
    }

    @Test
    void markFailedWritesFailureDecision() {
        NotificationDeliveryFailureDecision decision = new NotificationDeliveryFailureDecision(
            NotificationEventStatus.DELIVERY_FAILED.code(),
            2,
            LocalDateTime.of(2026, 6, 18, 10, 35),
            "One or more notification bindings failed"
        );

        updater.markFailed(event(), decision);

        UpdateWrapper<NotificationEvent> wrapper = capturedUpdate();
        assertThat(wrapper.getSqlSegment()).contains("status", "delivery_claimed_at", "delivery_claimed_by");
        assertThat(wrapper.getSqlSet()).contains(
            "status", "retry_count", "next_retry_at", "last_error",
            "delivery_claimed_at", "delivery_claimed_by", "updated_at"
        );
        assertThat(wrapper.getParamNameValuePairs())
            .containsValue(NotificationEventStatus.DELIVERY_FAILED.code())
            .containsValue(2)
            .containsValue(LocalDateTime.of(2026, 6, 18, 10, 35))
            .containsValue("One or more notification bindings failed")
            .containsValue(LocalDateTime.of(2026, 6, 18, 10, 30));
    }

    @Test
    void markDeliveredClearsRetrySchedulingAndLastError() {
        updater.markDelivered(event());

        UpdateWrapper<NotificationEvent> wrapper = capturedUpdate();
        assertThat(wrapper.getSqlSegment()).contains("status", "delivery_claimed_at", "delivery_claimed_by");
        assertThat(wrapper.getSqlSet()).contains(
            "status", "next_retry_at", "last_error", "delivery_claimed_at", "delivery_claimed_by", "updated_at"
        );
        assertThat(wrapper.getParamNameValuePairs())
            .containsValue(NotificationEventStatus.DELIVERED.code())
            .containsValue(LocalDateTime.of(2026, 6, 18, 10, 30));
    }

    private UpdateWrapper<NotificationEvent> capturedUpdate() {
        @SuppressWarnings("unchecked")
        ArgumentCaptor<UpdateWrapper<NotificationEvent>> wrapperCaptor = ArgumentCaptor.forClass(UpdateWrapper.class);
        org.mockito.Mockito.verify(eventMapper).update(wrapperCaptor.capture());
        return wrapperCaptor.getValue();
    }

    private NotificationEvent event() {
        NotificationEvent event = new NotificationEvent();
        event.setId(11L);
        event.setStatus(NotificationEventStatus.PUBLISHED.code());
        event.setDeliveryClaimedAt(LocalDateTime.of(2026, 6, 18, 10, 25));
        event.setDeliveryClaimedBy("worker-1");
        return event;
    }
}
