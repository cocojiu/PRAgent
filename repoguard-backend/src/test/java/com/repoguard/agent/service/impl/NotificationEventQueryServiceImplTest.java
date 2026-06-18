package com.repoguard.agent.service.impl;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.repoguard.agent.entity.NotificationDeliveryLog;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.mapper.NotificationDeliveryLogMapper;
import com.repoguard.agent.mapper.NotificationEventMapper;
import com.repoguard.agent.notification.NotificationDeliveryStatus;
import com.repoguard.agent.notification.NotificationDispatchService;
import com.repoguard.agent.notification.NotificationEventStatus;
import com.repoguard.agent.notification.NotificationEventType;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationEventQueryServiceImplTest {

    private final NotificationEventMapper eventMapper = org.mockito.Mockito.mock(NotificationEventMapper.class);
    private final NotificationDeliveryLogMapper deliveryLogMapper = org.mockito.Mockito.mock(NotificationDeliveryLogMapper.class);
    private final NotificationDispatchService dispatchService = org.mockito.Mockito.mock(NotificationDispatchService.class);
    private final NotificationEventQueryServiceImpl service = new NotificationEventQueryServiceImpl(
        eventMapper,
        deliveryLogMapper,
        dispatchService
    );

    @Test
    void listEventsReturnsDeliverySummary() {
        Page<NotificationEvent> page = Page.of(1, 20);
        page.setRecords(List.of(event()));
        page.setTotal(1);
        when(eventMapper.selectPage(any(), any())).thenReturn(page);
        when(deliveryLogMapper.selectList(any())).thenReturn(List.of(
            delivery(1L, "DINGTALK", NotificationDeliveryStatus.FAILED.code(), LocalDateTime.of(2026, 6, 15, 10, 0)),
            delivery(1L, "WECOM", NotificationDeliveryStatus.SUCCESS.code(), LocalDateTime.of(2026, 6, 15, 10, 1))
        ));

        var result = service.listEvents(1, 20, null, null);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items()).hasSize(1);
        var summary = result.items().getFirst().deliverySummary();
        assertThat(summary.providers()).containsExactly("DINGTALK", "WECOM");
        assertThat(summary.deliveryCount()).isEqualTo(2);
        assertThat(summary.failedDeliveryCount()).isEqualTo(1);
        assertThat(summary.latestDeliveryStatus()).isEqualTo(NotificationDeliveryStatus.SUCCESS.code());
    }

    @Test
    void retryEventResetsEventStatusAndPublishesExistingEvent() {
        NotificationEvent event = event();
        event.setStatus(NotificationEventStatus.DELIVERY_FAILED.code());
        event.setRetryCount(3);
        event.setNextRetryAt(LocalDateTime.of(2026, 6, 15, 10, 30));
        event.setLastError("timeout");
        when(eventMapper.selectById(1L)).thenReturn(event);
        when(deliveryLogMapper.selectList(any())).thenReturn(List.of());

        var result = service.retryEvent(1L);

        assertThat(event.getStatus()).isEqualTo(NotificationEventStatus.PENDING.code());
        assertThat(event.getRetryCount()).isZero();
        assertThat(event.getNextRetryAt()).isNotNull();
        assertThat(event.getLastError()).isNull();
        assertThat(result.status()).isEqualTo(NotificationEventStatus.PENDING.code());
        verify(eventMapper).updateById(event);
        verify(dispatchService).publishExistingEvent(1L);
    }

    @Test
    void listDeliveriesReturnsDeliveryDtos() {
        Page<NotificationDeliveryLog> page = Page.of(1, 20);
        page.setRecords(List.of(delivery(1L, "DINGTALK", NotificationDeliveryStatus.SUCCESS.code(), LocalDateTime.of(2026, 6, 15, 10, 0))));
        page.setTotal(1);
        when(deliveryLogMapper.selectPage(any(), any())).thenReturn(page);

        var result = service.listDeliveries(1, 20, "success", 100L);

        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items()).hasSize(1);
        assertThat(result.items().getFirst().provider()).isEqualTo("DINGTALK");
        assertThat(result.items().getFirst().status()).isEqualTo(NotificationDeliveryStatus.SUCCESS.code());
    }

    private NotificationEvent event() {
        NotificationEvent event = new NotificationEvent();
        event.setId(1L);
        event.setEventKey(NotificationEventType.REVIEW_COMPLETED.code() + ":100");
        event.setEventType(NotificationEventType.REVIEW_COMPLETED.code());
        event.setTaskId(100L);
        event.setStatus(NotificationEventStatus.DELIVERED.code());
        event.setRetryCount(0);
        event.setCreatedAt(LocalDateTime.of(2026, 6, 15, 9, 59));
        event.setUpdatedAt(LocalDateTime.of(2026, 6, 15, 10, 1));
        return event;
    }

    private NotificationDeliveryLog delivery(Long eventId, String provider, String status, LocalDateTime createdAt) {
        NotificationDeliveryLog delivery = new NotificationDeliveryLog();
        delivery.setId(eventId + 10);
        delivery.setEventId(eventId);
        delivery.setBindingId(7L);
        delivery.setTaskId(100L);
        delivery.setProvider(provider);
        delivery.setStatus(status);
        delivery.setAttemptCount(1);
        delivery.setCreatedAt(createdAt);
        delivery.setSentAt(createdAt);
        return delivery;
    }
}
