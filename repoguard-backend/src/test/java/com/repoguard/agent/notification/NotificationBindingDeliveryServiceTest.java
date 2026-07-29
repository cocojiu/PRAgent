package com.repoguard.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.entity.NotificationDeliveryLog;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.mapper.NotificationDeliveryLogMapper;
import com.repoguard.agent.notification.binding.NotificationBindingMatcher;
import com.repoguard.agent.notification.query.NotificationSuccessfulDeliveryQuery;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class NotificationBindingDeliveryServiceTest {

    private final NotificationDeliveryLogMapper deliveryLogMapper =
        org.mockito.Mockito.mock(NotificationDeliveryLogMapper.class);
    private final NotificationChannelAdapter adapter =
        org.mockito.Mockito.mock(NotificationChannelAdapter.class);
    private final NotificationBindingDeliveryService service = new NotificationBindingDeliveryService(
        deliveryLogMapper,
        registry(),
        new NotificationDeliveryLogFactory(new NotificationTextLimiter(), new NotificationRetrySchedule()),
        new NotificationBindingMatcher(),
        new NotificationSuccessfulDeliveryQuery(deliveryLogMapper)
    );

    @Test
    void sendsSupportedBindingAndStoresDeliveryLog() {
        when(deliveryLogMapper.selectCount(any())).thenReturn(0L);
        when(adapter.send(any(), any())).thenReturn(NotificationSendResult.success("request-1", "ok"));

        Optional<NotificationSendResult> result = service.deliver(event(), message(), binding());

        assertThat(result).contains(NotificationSendResult.success("request-1", "ok"));
        ArgumentCaptor<NotificationDeliveryLog> logCaptor =
            ArgumentCaptor.forClass(NotificationDeliveryLog.class);
        verify(deliveryLogMapper).insert(logCaptor.capture());
        assertThat(logCaptor.getValue().getStatus()).isEqualTo(NotificationDeliveryStatus.SUCCESS.code());
        assertThat(logCaptor.getValue().getBindingId()).isEqualTo(7L);
    }

    @Test
    void skipsBindingThatDoesNotSupportEventType() {
        NotificationChannelBinding binding = binding();
        binding.setNotifyReviewCompleted(false);

        Optional<NotificationSendResult> result = service.deliver(event(), message(), binding);

        assertThat(result).isEmpty();
        verify(adapter, never()).send(any(), any());
        verify(deliveryLogMapper, never()).selectCount(any());
    }

    @Test
    void skipsBindingThatWasAlreadyDeliveredSuccessfully() {
        when(deliveryLogMapper.selectCount(any())).thenReturn(1L);

        Optional<NotificationSendResult> result = service.deliver(event(), message(), binding());

        assertThat(result).isEmpty();
        verify(adapter, never()).send(any(), any());
        verify(deliveryLogMapper, never()).insert(any(NotificationDeliveryLog.class));
    }

    private NotificationChannelAdapterRegistry registry() {
        when(adapter.provider()).thenReturn("DINGTALK");
        return new NotificationChannelAdapterRegistry(List.of(adapter), new NotificationProviderKeyNormalizer());
    }

    private NotificationEvent event() {
        NotificationEvent event = new NotificationEvent();
        event.setId(11L);
        event.setTaskId(42L);
        event.setEventType(NotificationEventType.REVIEW_COMPLETED.code());
        event.setRetryCount(0);
        return event;
    }

    private NotificationMessage message() {
        return new NotificationMessage(
            NotificationEventType.REVIEW_COMPLETED.code(),
            42L,
            null,
            "octocat",
            "Hello-World",
            7,
            "Improve review flow",
            "COMPLETED",
            "LOW",
            1,
            0,
            0,
            0,
            "/repoguard/tasks/42"
        );
    }

    private NotificationChannelBinding binding() {
        NotificationChannelBinding binding = new NotificationChannelBinding();
        binding.setId(7L);
        binding.setProvider("DINGTALK");
        binding.setEnabled(true);
        binding.setNotifyReviewCompleted(true);
        return binding;
    }
}
