package com.repoguard.agent.notification.publish;

import com.repoguard.agent.notification.outbox.NotificationEventPayloadBuilder;
import com.repoguard.agent.notification.outbox.NotificationOutboxEventStore;
import com.repoguard.agent.review.quality.LlmModelReleaseAlertPublisher;
import com.repoguard.agent.review.quality.LlmModelReleaseMetricSnapshot;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Adapts aggregate release alerts to the durable notification outbox. */
@Component
public class LlmModelReleaseAlertPublisherImpl implements LlmModelReleaseAlertPublisher {

    private final NotificationOutboxEventStore outboxEventStore;
    private final NotificationEventPayloadBuilder payloadBuilder;
    private final NotificationEventPublishCoordinator publishCoordinator;

    public LlmModelReleaseAlertPublisherImpl(
        NotificationOutboxEventStore outboxEventStore,
        NotificationEventPayloadBuilder payloadBuilder,
        NotificationEventPublishCoordinator publishCoordinator
    ) {
        this.outboxEventStore = Objects.requireNonNull(outboxEventStore, "outboxEventStore");
        this.payloadBuilder = Objects.requireNonNull(payloadBuilder, "payloadBuilder");
        this.publishCoordinator = Objects.requireNonNull(publishCoordinator, "publishCoordinator");
    }

    @Override
    public void publish(LlmModelReleaseMetricSnapshot snapshot) {
        if (snapshot == null || snapshot.alertCodes().isEmpty()) return;
        var payload = payloadBuilder.buildReleaseAlert(snapshot);
        var event = outboxEventStore.createPendingEvent("MODEL_RELEASE_ALERT", payload);
        publishCoordinator.publishAfterCommit(event);
    }
}
