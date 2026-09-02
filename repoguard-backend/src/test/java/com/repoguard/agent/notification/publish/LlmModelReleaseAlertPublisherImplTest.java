package com.repoguard.agent.notification.publish;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.notification.outbox.NotificationEventPayload;
import com.repoguard.agent.notification.outbox.NotificationEventPayloadBuilder;
import com.repoguard.agent.notification.outbox.NotificationOutboxEventStore;
import com.repoguard.agent.review.quality.LlmModelReleaseAlertPublisher;
import com.repoguard.agent.review.quality.LlmModelReleaseMetricSnapshot;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class LlmModelReleaseAlertPublisherImplTest {

    private final NotificationOutboxEventStore outbox = org.mockito.Mockito.mock(NotificationOutboxEventStore.class);
    private final NotificationEventPayloadBuilder payloadBuilder = org.mockito.Mockito.mock(NotificationEventPayloadBuilder.class);
    private final NotificationEventPublishCoordinator publishCoordinator = org.mockito.Mockito.mock(NotificationEventPublishCoordinator.class);
    private final LlmModelReleaseAlertPublisher publisher = new LlmModelReleaseAlertPublisherImpl(
        outbox, payloadBuilder, publishCoordinator
    );

    @Test
    void persistsAndPublishesReleaseAlertAfterCommit() {
        LlmModelReleaseMetricSnapshot snapshot = snapshot(List.of("P95_LATENCY_ABOVE_RUNTIME_THRESHOLD"));
        NotificationEventPayload payload = new NotificationEventPayload("event-key", null, "{}");
        NotificationEvent event = new NotificationEvent();
        event.setId(9L);
        when(payloadBuilder.buildReleaseAlert(snapshot)).thenReturn(payload);
        when(outbox.createPendingEvent("MODEL_RELEASE_ALERT", payload)).thenReturn(event);

        publisher.publish(snapshot);

        verify(payloadBuilder).buildReleaseAlert(snapshot);
        verify(outbox).createPendingEvent("MODEL_RELEASE_ALERT", payload);
        verify(publishCoordinator).publishAfterCommit(event);
    }

    @Test
    void ignoresNullAndNonAlertSnapshots() {
        publisher.publish(null);
        publisher.publish(snapshot(List.of()));

        verify(payloadBuilder, never()).buildReleaseAlert(org.mockito.ArgumentMatchers.any());
        verify(outbox, never()).createPendingEvent(org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(NotificationEventPayload.class));
    }

    private LlmModelReleaseMetricSnapshot snapshot(List<String> codes) {
        return new LlmModelReleaseMetricSnapshot(
            8L, 7L, "release-next", "openai", "gpt-next",
            LocalDateTime.of(2026, 9, 3, 1, 0), LocalDateTime.of(2026, 9, 3, 2, 0),
            12L, 1200L, new BigDecimal("0.1200"), 1200L, 0L, 0L, 0L,
            codes.isEmpty() ? "NORMAL" : "ALERT", codes, codes.isEmpty() ? "NONE" : "NOTIFY",
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
            LocalDateTime.of(2026, 9, 3, 2, 0), LocalDateTime.of(2026, 9, 3, 2, 0)
        );
    }
}
