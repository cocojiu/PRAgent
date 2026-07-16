package com.repoguard.agent.notification;

import java.time.LocalDateTime;
import java.util.Objects;

record NotificationDeliveryClaim(
    LocalDateTime claimedAt,
    String claimedBy
) {

    NotificationDeliveryClaim {
        Objects.requireNonNull(claimedAt, "claimedAt");
        Objects.requireNonNull(claimedBy, "claimedBy");
    }
}
