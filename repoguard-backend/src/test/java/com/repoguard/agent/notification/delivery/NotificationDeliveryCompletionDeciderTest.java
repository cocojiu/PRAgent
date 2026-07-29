package com.repoguard.agent.notification.delivery;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.notification.NotificationEventStatus;
import com.repoguard.agent.notification.NotificationEventType;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class NotificationDeliveryCompletionDeciderTest {

    private final NotificationDeliveryFailurePolicy deliveryFailurePolicy =
        org.mockito.Mockito.mock(NotificationDeliveryFailurePolicy.class);
    private final NotificationDeliveryCompletionDecider decider =
        new NotificationDeliveryCompletionDecider(deliveryFailurePolicy);

    @Test
    void decidesDeliveredWhenNoDeliveryFailed() {
        NotificationEvent event = event();
        NotificationDeliveryResultSummary resultSummary = NotificationDeliveryResultSummary.empty()
            .add(NotificationSendResult.success("request-1", "ok"));

        NotificationDeliveryCompletionDecision decision = decider.decide(event, resultSummary);

        assertThat(decision.delivered()).isTrue();
        assertThat(decision.failureDecision()).isNull();
        verify(deliveryFailurePolicy, never()).decide(event);
    }

    @Test
    void decidesFailedWithFailurePolicyWhenAnyDeliveryFailed() {
        NotificationEvent event = event();
        NotificationDeliveryResultSummary resultSummary = NotificationDeliveryResultSummary.empty()
            .add(NotificationSendResult.success("request-1", "ok"))
            .add(NotificationSendResult.failed("request-2", "timeout"));
        NotificationDeliveryFailureDecision failureDecision = new NotificationDeliveryFailureDecision(
            NotificationEventStatus.DELIVERY_FAILED.code(),
            1,
            LocalDateTime.of(2026, 6, 22, 18, 0),
            "One or more notification bindings failed"
        );
        when(deliveryFailurePolicy.decide(event)).thenReturn(failureDecision);

        NotificationDeliveryCompletionDecision decision = decider.decide(event, resultSummary);

        assertThat(decision.delivered()).isFalse();
        assertThat(decision.failureDecision()).isEqualTo(failureDecision);
        verify(deliveryFailurePolicy).decide(event);
    }

    private NotificationEvent event() {
        NotificationEvent event = new NotificationEvent();
        event.setId(11L);
        event.setTaskId(42L);
        event.setEventType(NotificationEventType.REVIEW_COMPLETED.code());
        event.setRetryCount(0);
        return event;
    }
}
