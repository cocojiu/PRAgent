package com.repoguard.agent.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.config.RabbitNotificationQueueProperties;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.mapper.NotificationEventMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationEventPublishCompensatorTest {

    private final NotificationEventMapper eventMapper = org.mockito.Mockito.mock(NotificationEventMapper.class);
    private final NotificationDispatchService dispatchService = org.mockito.Mockito.mock(NotificationDispatchService.class);
    private final RabbitNotificationQueueProperties properties = new RabbitNotificationQueueProperties();

    @Test
    void compensatesDeliveryFailedEventsForThirdPartyRetry() {
        NotificationEvent event = new NotificationEvent();
        event.setId(7L);
        event.setStatus("DELIVERY_FAILED");
        event.setRetryCount(1);
        when(eventMapper.selectList(any())).thenReturn(List.of(event));

        new NotificationEventPublishCompensator(eventMapper, dispatchService, properties).compensate();

        verify(dispatchService).publishExistingEvent(7L);
    }
}
