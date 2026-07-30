package com.repoguard.agent.notification.delivery;

import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.notification.NotificationEventStatus;
import com.repoguard.agent.notification.query.NotificationDeliverableEventQuery;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class NotificationDeliveryClaimService {

    private final NotificationDeliverableEventQuery deliverableEventQuery;
    private final NotificationDeliveryEventStateUpdater eventStateUpdater;
    private final Clock clock;
    private final String instanceId;

    @Autowired
    public NotificationDeliveryClaimService(
        NotificationDeliverableEventQuery deliverableEventQuery,
        NotificationDeliveryEventStateUpdater eventStateUpdater
    ) {
        this(
            deliverableEventQuery,
            eventStateUpdater,
            Clock.systemDefaultZone(),
            "repoguard-notification-delivery-" + UUID.randomUUID()
        );
    }

    public NotificationDeliveryClaimService(
        NotificationDeliverableEventQuery deliverableEventQuery,
        NotificationDeliveryEventStateUpdater eventStateUpdater,
        Clock clock,
        String instanceId
    ) {
        this.deliverableEventQuery = Objects.requireNonNull(deliverableEventQuery, "deliverableEventQuery");
        this.eventStateUpdater = Objects.requireNonNull(eventStateUpdater, "eventStateUpdater");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.instanceId = Objects.requireNonNull(instanceId, "instanceId");
    }

    public Optional<NotificationEvent> claim(Long eventId) {
        Optional<NotificationEvent> deliverableEvent = deliverableEventQuery.load(eventId);
        if (deliverableEvent.isEmpty()) {
            return Optional.empty();
        }
        NotificationEvent event = deliverableEvent.get();
        NotificationDeliveryClaim claim = new NotificationDeliveryClaim(LocalDateTime.now(clock), instanceId);
        if (!eventStateUpdater.claimForDelivery(event, claim)) {
            return Optional.empty();
        }
        event.setStatus(NotificationEventStatus.DELIVERING.code());
        event.setPublishClaimedAt(null);
        event.setPublishClaimedBy(null);
        event.setDeliveryClaimedAt(claim.claimedAt());
        event.setDeliveryClaimedBy(claim.claimedBy());
        event.setUpdatedAt(claim.claimedAt());
        return Optional.of(event);
    }
}
