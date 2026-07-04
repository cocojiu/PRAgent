package com.repoguard.agent.notification;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class NotificationTextLimiterTest {

    private final NotificationTextLimiter limiter = new NotificationTextLimiter();

    @Test
    void limitKeepsNullAndShortValuesUnchanged() {
        assertThat(limiter.limit(null, 4)).isNull();
        assertThat(limiter.limit("ok", 4)).isEqualTo("ok");
        assertThat(limiter.limit("okay", 4)).isEqualTo("okay");
    }

    @Test
    void limitTruncatesValuesBeyondMaxLength() {
        assertThat(limiter.limit("abcdef", 4)).isEqualTo("abcd");
    }
}
