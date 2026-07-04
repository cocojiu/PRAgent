package com.repoguard.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.entity.NotificationEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class NotificationDeliveryFailurePolicyTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-18T10:30:00Z"), ZoneId.of("UTC"));
    private final NotificationDeliveryFailurePolicy policy =
        new NotificationDeliveryFailurePolicy(new NotificationRetrySchedule(clock));

    @Test
    void firstFailureSchedulesDeliveryFailedWithOneMinuteRetry() {
        NotificationDeliveryFailureDecision decision = policy.decide(event(null));

        assertThat(decision.status()).isEqualTo(NotificationEventStatus.DELIVERY_FAILED.code());
        assertThat(decision.retryCount()).isEqualTo(1);
        assertThat(decision.nextRetryAt()).isEqualTo(LocalDateTime.of(2026, 6, 18, 10, 31));
        assertThat(decision.lastError()).isEqualTo("One or more notification bindings failed");
    }

    @Test
    void fourthFailureUsesThirtyMinuteRetry() {
        NotificationDeliveryFailureDecision decision = policy.decide(event(3));

        assertThat(decision.status()).isEqualTo(NotificationEventStatus.DELIVERY_FAILED.code());
        assertThat(decision.retryCount()).isEqualTo(4);
        assertThat(decision.nextRetryAt()).isEqualTo(LocalDateTime.of(2026, 6, 18, 11, 0));
    }

    @Test
    void reachingMaxAttemptsMarksDeadWithoutNextRetry() {
        NotificationDeliveryFailureDecision decision = policy.decide(event(4));

        assertThat(decision.status()).isEqualTo(NotificationEventStatus.DEAD.code());
        assertThat(decision.retryCount()).isEqualTo(5);
        assertThat(decision.nextRetryAt()).isNull();
        assertThat(decision.lastError()).isEqualTo("One or more notification bindings failed");
    }

    private NotificationEvent event(Integer retryCount) {
        NotificationEvent event = new NotificationEvent();
        event.setRetryCount(retryCount);
        return event;
    }
}
