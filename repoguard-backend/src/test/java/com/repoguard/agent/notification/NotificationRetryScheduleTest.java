package com.repoguard.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import org.junit.jupiter.api.Test;

class NotificationRetryScheduleTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-06-18T08:30:00Z"), ZoneId.of("UTC"));
    private final NotificationRetrySchedule schedule = new NotificationRetrySchedule(clock);

    @Test
    void nextRetryCountTreatsMissingCountAsFirstAttempt() {
        assertThat(schedule.nextRetryCount(null)).isEqualTo(1);
        assertThat(schedule.nextRetryCount(3)).isEqualTo(4);
    }

    @Test
    void nextRetryAtUsesConfiguredBackoffBuckets() {
        assertThat(schedule.nextRetryAt(1)).isEqualTo(LocalDateTime.of(2026, 6, 18, 8, 31));
        assertThat(schedule.nextRetryAt(4)).isEqualTo(LocalDateTime.of(2026, 6, 18, 9, 0));
        assertThat(schedule.nextRetryAt(6)).isEqualTo(LocalDateTime.of(2026, 6, 18, 9, 30));
    }
}
