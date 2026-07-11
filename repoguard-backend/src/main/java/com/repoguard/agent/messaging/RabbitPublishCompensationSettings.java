package com.repoguard.agent.messaging;

import java.time.LocalDateTime;
import java.util.Objects;

public class RabbitPublishCompensationSettings {

    private final RabbitPublishCompensationProperties properties;
    private final RabbitPublishCompensationPolicy compensationPolicy;

    public RabbitPublishCompensationSettings(
        RabbitPublishCompensationProperties properties,
        RabbitPublishCompensationPolicy compensationPolicy
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.compensationPolicy = Objects.requireNonNull(compensationPolicy, "compensationPolicy");
    }

    public LocalDateTime expiredBefore(LocalDateTime now) {
        return compensationPolicy.expiredBefore(now, properties.getPublishCompensationLeaseMs());
    }

    public RabbitPublishClaim claim(LocalDateTime claimedAt, String instanceId) {
        LocalDateTime databaseClaimedAt = claimedAt.withNano(0);
        return new RabbitPublishClaim(
            databaseClaimedAt,
            instanceId,
            expiredBefore(databaseClaimedAt),
            maxAttempts()
        );
    }

    public LocalDateTime nextRetryAt(LocalDateTime now) {
        return compensationPolicy.nextRetryAt(now, properties);
    }

    public int nextAttempt(Integer currentAttempts) {
        return compensationPolicy.nextAttempt(currentAttempts);
    }

    public int maxAttempts() {
        return compensationPolicy.maxAttempts(properties);
    }

    public int batchSize() {
        return compensationPolicy.batchSize(properties);
    }
}
