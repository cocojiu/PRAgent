package com.repoguard.agent.messaging;

import java.time.LocalDateTime;
import java.util.Objects;

public record RabbitPublishClaim(
    LocalDateTime claimedAt,
    String instanceId,
    LocalDateTime expiredBefore,
    int maxAttempts
) {

    public RabbitPublishClaim {
        Objects.requireNonNull(claimedAt, "claimedAt");
        Objects.requireNonNull(instanceId, "instanceId");
        Objects.requireNonNull(expiredBefore, "expiredBefore");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be positive");
        }
    }
}
