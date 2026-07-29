package com.repoguard.agent.notification;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.notification.delivery.NotificationDeliveryCompletionDecision;
import com.repoguard.agent.notification.delivery.NotificationDeliveryFailureDecision;
import com.repoguard.agent.notification.delivery.NotificationDeliveryResultSummary;
import com.repoguard.agent.notification.delivery.NotificationSendResult;
import org.junit.jupiter.api.Test;

class NotificationDeliveryCompletionServiceTest {

    private final NotificationDeliveryCompletionDecider completionDecider =
        org.mockito.Mockito.mock(NotificationDeliveryCompletionDecider.class);
    private final NotificationDeliveryEventStateUpdater eventStateUpdater =
        org.mockito.Mockito.mock(NotificationDeliveryEventStateUpdater.class);
    private final NotificationDeliveryCompletionService service =
        new NotificationDeliveryCompletionService(completionDecider, eventStateUpdater);

    @Test
    void marksEventDeliveredWhenSummaryHasNoFailures() {
        NotificationEvent event = event();
        NotificationDeliveryResultSummary resultSummary = NotificationDeliveryResultSummary.empty()
            .add(NotificationSendResult.success("request-1", "ok"));
        when(completionDecider.decide(event, resultSummary))
            .thenReturn(NotificationDeliveryCompletionDecision.markDelivered());

        service.complete(event, resultSummary);

        verify(completionDecider).decide(event, resultSummary);
        verify(eventStateUpdater).markDelivered(event);
        verify(eventStateUpdater, never()).markFailed(any(), any());
    }

    @Test
    void marksEventFailedWithFailureDecisionWhenAnyDeliveryFailed() {
        NotificationEvent event = event();
        NotificationDeliveryFailureDecision decision = new NotificationDeliveryFailureDecision(
            NotificationEventStatus.DELIVERY_FAILED.code(),
            1,
            java.time.LocalDateTime.of(2026, 6, 19, 0, 30),
            "One or more notification bindings failed"
        );
        NotificationDeliveryResultSummary resultSummary = NotificationDeliveryResultSummary.empty()
            .add(NotificationSendResult.success("request-1", "ok"))
            .add(NotificationSendResult.failed("request-2", "timeout"));
        when(completionDecider.decide(event, resultSummary))
            .thenReturn(NotificationDeliveryCompletionDecision.failed(decision));

        service.complete(event, resultSummary);

        verify(completionDecider).decide(event, resultSummary);
        verify(eventStateUpdater).markFailed(event, decision);
        verify(eventStateUpdater, never()).markDelivered(any());
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
