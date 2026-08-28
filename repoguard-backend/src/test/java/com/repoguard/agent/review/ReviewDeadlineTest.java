package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

class ReviewDeadlineTest {

    @Test
    void usesMonotonicClockAndReportsTheExhaustedStage() {
        AtomicLong clock = new AtomicLong(1_000L);
        ReviewDeadline deadline = ReviewDeadline.startingAt(1_000L, Duration.ofNanos(500L), clock::get);

        assertThat(deadline.remainingNanos()).isEqualTo(500L);
        clock.set(1_500L);

        assertThat(deadline.exhausted()).isTrue();
        assertThatThrownBy(() -> deadline.requireRemaining("rule_scan"))
            .isInstanceOf(ReviewBudgetExceededException.class)
            .hasMessageContaining("rule_scan");
    }
}
