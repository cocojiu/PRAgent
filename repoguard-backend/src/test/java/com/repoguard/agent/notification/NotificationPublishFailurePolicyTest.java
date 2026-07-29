package com.repoguard.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.messaging.RabbitPublishCompensationPolicy;
import com.repoguard.agent.notification.outbox.NotificationPublishFailureDecision;
import com.repoguard.agent.notification.retry.NotificationRetrySchedule;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class NotificationPublishFailurePolicyTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-18T08:30:00Z"), ZoneId.of("UTC"));
    private final NotificationPublishFailurePolicy policy =
        new NotificationPublishFailurePolicy(
            new NotificationRetrySchedule(clock),
            new NotificationTextLimiter(),
            new RabbitPublishCompensationPolicy()
        );

    @Test
    void firstFailureSchedulesPublishFailedWithOneMinuteRetry() {
        NotificationEvent event = event(null);

        NotificationPublishFailureDecision decision = policy.decide(event, new RuntimeException("confirm timed out"), 5);

        assertThat(decision.status()).isEqualTo(NotificationEventStatus.PUBLISH_FAILED.code());
        assertThat(decision.retryCount()).isEqualTo(1);
        assertThat(decision.nextRetryAt()).isEqualTo(LocalDateTime.of(2026, 6, 18, 8, 31));
        assertThat(decision.lastError()).isEqualTo("confirm timed out");
    }

    @Test
    void reachingMaxAttemptsMarksDeadWithoutNextRetry() {
        NotificationEvent event = event(4);

        NotificationPublishFailureDecision decision = policy.decide(event, new RuntimeException("broker unavailable"), 5);

        assertThat(decision.status()).isEqualTo(NotificationEventStatus.DEAD.code());
        assertThat(decision.retryCount()).isEqualTo(5);
        assertThat(decision.nextRetryAt()).isNull();
        assertThat(decision.lastError()).isEqualTo("broker unavailable");
    }

    @Test
    void nonPositiveMaxAttemptsTreatsFirstFailureAsDead() {
        NotificationEvent event = event(0);

        NotificationPublishFailureDecision decision = policy.decide(event, new RuntimeException("failed"), 0);

        assertThat(decision.status()).isEqualTo(NotificationEventStatus.DEAD.code());
        assertThat(decision.retryCount()).isEqualTo(1);
        assertThat(decision.nextRetryAt()).isNull();
    }

    @Test
    void nullExceptionMessageFallsBackToClassName() {
        NotificationEvent event = event(0);

        NotificationPublishFailureDecision decision = policy.decide(event, new RuntimeException(), 5);

        assertThat(decision.lastError()).isEqualTo("RuntimeException");
    }

    @Test
    void sensitiveFailureMessageIsSanitizedBeforePersisting() {
        NotificationEvent event = event(0);

        NotificationPublishFailureDecision decision = policy.decide(event, new RuntimeException(
            "amqp://user:raw-pass@rabbit:5672 failed token=raw-token password=raw-password"
        ), 5);

        assertThat(decision.lastError()).contains("amqp://user:****@rabbit:5672");
        assertThat(decision.lastError()).contains("token=****", "password=****");
        assertThat(decision.lastError()).doesNotContain("raw-pass", "raw-token", "raw-password");
    }

    @Test
    void longErrorMessageIsTruncatedToDatabaseLimit() {
        NotificationEvent event = event(0);
        String message = "x".repeat(1100);

        NotificationPublishFailureDecision decision = policy.decide(event, new RuntimeException(message), 5);

        assertThat(decision.lastError()).hasSize(1024);
        assertThat(decision.lastError()).isEqualTo("x".repeat(1024));
    }

    @Test
    void retryDelayUsesLastScheduleBucketAfterFifthAttempt() {
        NotificationEvent event = event(5);

        NotificationPublishFailureDecision decision = policy.decide(event, new RuntimeException("failed"), 10);

        assertThat(decision.status()).isEqualTo(NotificationEventStatus.PUBLISH_FAILED.code());
        assertThat(decision.retryCount()).isEqualTo(6);
        assertThat(decision.nextRetryAt()).isEqualTo(LocalDateTime.of(2026, 6, 18, 9, 30));
    }

    private NotificationEvent event(Integer retryCount) {
        NotificationEvent event = new NotificationEvent();
        event.setRetryCount(retryCount);
        return event;
    }
}
