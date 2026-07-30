package com.repoguard.agent.notification.retry;

import java.time.Clock;
import java.time.LocalDateTime;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public class NotificationRetrySchedule {

    private static final int[] RETRY_MINUTES = {1, 5, 15, 30, 60};

    private final Clock clock;

    @Autowired
    public NotificationRetrySchedule() {
        this(Clock.systemDefaultZone());
    }

    public NotificationRetrySchedule(Clock clock) {
        this.clock = clock;
    }

    public int nextRetryCount(Integer retryCount) {
        return (retryCount == null ? 0 : retryCount) + 1;
    }

    public LocalDateTime nextRetryAt(int retryCount) {
        int index = Math.min(Math.max(0, retryCount - 1), RETRY_MINUTES.length - 1);
        return LocalDateTime.now(clock).plusMinutes(RETRY_MINUTES[index]);
    }
}
