package com.repoguard.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.entity.NotificationChannelBinding;
import com.repoguard.agent.entity.NotificationDeliveryLog;
import com.repoguard.agent.entity.NotificationEvent;
import com.repoguard.agent.notification.delivery.NotificationDeliveryStatus;
import com.repoguard.agent.notification.delivery.NotificationSendResult;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class NotificationDeliveryLogFactoryTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-18T10:30:00Z"), ZoneId.of("UTC"));
    private final NotificationDeliveryLogFactory factory =
        new NotificationDeliveryLogFactory(
            clock,
            new NotificationTextLimiter(),
            new NotificationRetrySchedule(clock)
        );

    @Test
    void successResultCreatesSuccessLogAndClearsFailureReason() {
        NotificationDeliveryLog log = factory.create(
            event(2),
            binding(),
            NotificationSendResult.success("request-1", "ok")
        );

        assertThat(log.getEventId()).isEqualTo(11L);
        assertThat(log.getBindingId()).isEqualTo(7L);
        assertThat(log.getTaskId()).isEqualTo(42L);
        assertThat(log.getProvider()).isEqualTo("DINGTALK");
        assertThat(log.getStatus()).isEqualTo(NotificationDeliveryStatus.SUCCESS.code());
        assertThat(log.getAttemptCount()).isEqualTo(3);
        assertThat(log.getFailureReason()).isNull();
        assertThat(log.getRequestId()).isEqualTo("request-1");
        assertThat(log.getSentAt()).isEqualTo(LocalDateTime.of(2026, 6, 18, 10, 30));
        assertThat(log.getCreatedAt()).isEqualTo(LocalDateTime.of(2026, 6, 18, 10, 30));
    }

    @Test
    void failedResultCreatesFailedLogWithFailureReason() {
        NotificationDeliveryLog log = factory.create(
            event(null),
            binding(),
            NotificationSendResult.failed("request-2", "timeout")
        );

        assertThat(log.getStatus()).isEqualTo(NotificationDeliveryStatus.FAILED.code());
        assertThat(log.getAttemptCount()).isEqualTo(1);
        assertThat(log.getFailureReason()).isEqualTo("timeout");
        assertThat(log.getRequestId()).isEqualTo("request-2");
    }

    @Test
    void longFailureReasonIsTruncatedToDatabaseLimit() {
        NotificationDeliveryLog log = factory.create(
            event(0),
            binding(),
            NotificationSendResult.failed(null, "x".repeat(1100))
        );

        assertThat(log.getFailureReason()).hasSize(1024);
        assertThat(log.getFailureReason()).isEqualTo("x".repeat(1024));
    }

    private NotificationEvent event(Integer retryCount) {
        NotificationEvent event = new NotificationEvent();
        event.setId(11L);
        event.setTaskId(42L);
        event.setRetryCount(retryCount);
        return event;
    }

    private NotificationChannelBinding binding() {
        NotificationChannelBinding binding = new NotificationChannelBinding();
        binding.setId(7L);
        binding.setProvider("DINGTALK");
        return binding;
    }
}
