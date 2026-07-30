package com.repoguard.agent.notification.delivery;

import java.time.LocalDateTime;
import java.util.Objects;

public record NotificationDeliveryClaim(
    LocalDateTime claimedAt,
    String claimedBy
) {

    public NotificationDeliveryClaim {
        Objects.requireNonNull(claimedAt, "claimedAt");
        Objects.requireNonNull(claimedBy, "claimedBy");
    }
}
