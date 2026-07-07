package com.repoguard.agent.messaging;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class RabbitPublishClaimTest {

    private final LocalDateTime now = LocalDateTime.of(2026, 7, 7, 10, 0);

    @Test
    void constructorRejectsMissingClaimedAt() {
        assertThatThrownBy(() -> new RabbitPublishClaim(null, "node-a", now.minusMinutes(2), 5))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("claimedAt");
    }

    @Test
    void constructorRejectsMissingInstanceId() {
        assertThatThrownBy(() -> new RabbitPublishClaim(now, null, now.minusMinutes(2), 5))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("instanceId");
    }

    @Test
    void constructorRejectsMissingExpiredBefore() {
        assertThatThrownBy(() -> new RabbitPublishClaim(now, "node-a", null, 5))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("expiredBefore");
    }

    @Test
    void constructorRejectsInvalidMaxAttempts() {
        assertThatThrownBy(() -> new RabbitPublishClaim(now, "node-a", now.minusMinutes(2), 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("maxAttempts must be positive");
    }
}
