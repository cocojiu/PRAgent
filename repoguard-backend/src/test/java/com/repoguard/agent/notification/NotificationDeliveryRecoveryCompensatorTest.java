package com.repoguard.agent.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.config.RabbitNotificationQueueProperties;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.mapper.NotificationEventMapper;
import com.repoguard.agent.notification.delivery.NotificationDeliveryFailureDecision;
import com.repoguard.agent.notification.delivery.NotificationDeliveryFailurePolicy;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.Test;

class NotificationDeliveryRecoveryCompensatorTest {

    private final NotificationEventMapper eventMapper = org.mockito.Mockito.mock(NotificationEventMapper.class);
    private final NotificationDeliveryEventStateUpdater stateUpdater =
        org.mockito.Mockito.mock(NotificationDeliveryEventStateUpdater.class);
    private final NotificationDeliveryFailurePolicy failurePolicy =
        org.mockito.Mockito.mock(NotificationDeliveryFailurePolicy.class);
    private final RabbitNotificationQueueProperties properties = new RabbitNotificationQueueProperties();
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-15T09:10:00Z"), ZoneOffset.UTC);

    @Test
    void recoversExpiredClaimThroughOwnedStateTransition() {
        NotificationEvent event = new NotificationEvent();
        event.setId(11L);
        event.setStatus(NotificationEventStatus.DELIVERING.code());
        event.setRetryCount(1);
        event.setDeliveryClaimedAt(LocalDateTime.of(2026, 7, 15, 9, 0));
        event.setDeliveryClaimedBy("worker-a");
        NotificationDeliveryFailureDecision decision = new NotificationDeliveryFailureDecision(
            NotificationEventStatus.DELIVERY_FAILED.code(),
            2,
            LocalDateTime.of(2026, 7, 15, 9, 15),
            "failed"
        );
        when(eventMapper.selectList(any(LambdaQueryWrapper.class))).thenReturn(List.of(event));
        when(failurePolicy.decide(event)).thenReturn(decision);
        when(stateUpdater.recoverExpired(event, decision)).thenReturn(true);

        compensator().recoverExpiredClaims();

        verify(stateUpdater).recoverExpired(event, decision);
    }

    private NotificationDeliveryRecoveryCompensator compensator() {
        return new NotificationDeliveryRecoveryCompensator(
            eventMapper,
            stateUpdater,
            failurePolicy,
            properties,
            clock
        );
    }
}
