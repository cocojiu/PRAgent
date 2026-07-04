package com.repoguard.agent.worker;

import java.time.Duration;
import java.time.LocalDateTime;
import org.springframework.stereotype.Component;

@Component
class ReviewTaskDurationPolicy {

    int durationSeconds(LocalDateTime startedAt, LocalDateTime finishedAt) {
        return (int) Duration.between(startedAt, finishedAt).toSeconds();
    }
}
