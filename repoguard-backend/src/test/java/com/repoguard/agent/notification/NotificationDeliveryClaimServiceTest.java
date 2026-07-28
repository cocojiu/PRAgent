package com.repoguard.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.NotificationEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class NotificationDeliveryClaimServiceTest {

    private final NotificationDeliverableEventQuery query =
        org.mockito.Mockito.mock(NotificationDeliverableEventQuery.class);
    private final NotificationDeliveryEventStateUpdater stateUpdater =
        org.mockito.Mockito.mock(NotificationDeliveryEventStateUpdater.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-15T09:00:00Z"), ZoneOffset.UTC);
    private final NotificationDeliveryClaimService service = new NotificationDeliveryClaimService(
        query,
        stateUpdater,
        clock,
        "worker-a"
    );

    @Test
    void returnsClaimedEventOnlyWhenAtomicUpdateSucceeds() {
        NotificationEvent event = event();
        when(query.load(11L)).thenReturn(Optional.of(event));
        when(stateUpdater.claimForDelivery(
            org.mockito.Mockito.same(event),
            org.mockito.ArgumentMatchers.any(NotificationDeliveryClaim.class)
        )).thenReturn(true);

        Optional<NotificationEvent> result = service.claim(11L);

        assertThat(result).containsSame(event);
        assertThat(event.getStatus()).isEqualTo(NotificationEventStatus.DELIVERING.code());
        assertThat(event.getPublishClaimedAt()).isNull();
        assertThat(event.getPublishClaimedBy()).isNull();
        assertThat(event.getDeliveryClaimedAt()).isEqualTo(LocalDateTime.of(2026, 7, 15, 9, 0));
        assertThat(event.getDeliveryClaimedBy()).isEqualTo("worker-a");
    }

    @Test
    void rejectsSecondConsumerWhenAtomicUpdateLosesRace() {
        NotificationEvent event = event();
        when(query.load(11L)).thenReturn(Optional.of(event));
        when(stateUpdater.claimForDelivery(
            org.mockito.Mockito.same(event),
            org.mockito.ArgumentMatchers.any(NotificationDeliveryClaim.class)
        )).thenReturn(false);

        assertThat(service.claim(11L)).isEmpty();

        verify(query).load(11L);
        assertThat(event.getDeliveryClaimedAt()).isNull();
    }

    private NotificationEvent event() {
        NotificationEvent event = new NotificationEvent();
        event.setId(11L);
        event.setStatus(NotificationEventStatus.PUBLISHING.code());
        event.setPublishClaimedAt(LocalDateTime.of(2026, 7, 15, 8, 59));
        event.setPublishClaimedBy("publisher-a");
        return event;
    }
}
