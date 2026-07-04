package com.repoguard.agent.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class ReviewTaskDurationPolicyTest {

    private final ReviewTaskDurationPolicy policy = new ReviewTaskDurationPolicy();

    @Test
    void calculatesDurationSeconds() {
        LocalDateTime startedAt = LocalDateTime.parse("2026-07-05T01:10:00");
        LocalDateTime finishedAt = startedAt.plusSeconds(75);

        assertThat(policy.durationSeconds(startedAt, finishedAt)).isEqualTo(75);
    }
}
