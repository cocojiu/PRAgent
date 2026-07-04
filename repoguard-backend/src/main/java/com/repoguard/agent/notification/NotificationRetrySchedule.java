package com.repoguard.agent.notification;

import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
class NotificationRetrySchedule {

    private static final int[] RETRY_MINUTES = {1, 5, 15, 30, 60};

    private final Clock clock;

    @Autowired
    NotificationRetrySchedule() {
        this(Clock.systemDefaultZone());
    }

    NotificationRetrySchedule(Clock clock) {
        this.clock = clock;
    }

    int nextRetryCount(Integer retryCount) {
        return (retryCount == null ? 0 : retryCount) + 1;
    }

    LocalDateTime nextRetryAt(int retryCount) {
        int index = Math.min(Math.max(0, retryCount - 1), RETRY_MINUTES.length - 1);
        return LocalDateTime.now(clock).plusMinutes(RETRY_MINUTES[index]);
    }
}
