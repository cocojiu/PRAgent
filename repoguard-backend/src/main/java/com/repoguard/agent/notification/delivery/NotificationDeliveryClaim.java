package com.repoguard.agent.notification.delivery;

import java.time.LocalDateTime;
import java.util.Objects;

public record NotificationDeliveryClaim(
    LocalDateTime claimedAt,
    String claimedBy
) {

    public NotificationDeliveryClaim {
        claimedAt = Objects.requireNonNull(claimedAt, "claimedAt").withNano(0);
        Objects.requireNonNull(claimedBy, "claimedBy");
    }
}
