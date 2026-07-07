package com.repoguard.agent.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.repoguard.agent.config.RabbitReviewQueueProperties;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class RabbitPublishCompensationSettingsTest {

    private final RabbitReviewQueueProperties properties = new RabbitReviewQueueProperties();
    private final RabbitPublishCompensationPolicy compensationPolicy = new RabbitPublishCompensationPolicy();

    @Test
    void constructorRejectsMissingProperties() {
        assertThatThrownBy(() -> new RabbitPublishCompensationSettings(null, compensationPolicy))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("properties");
    }

    @Test
    void constructorRejectsMissingCompensationPolicy() {
        assertThatThrownBy(() -> new RabbitPublishCompensationSettings(properties, null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("compensationPolicy");
    }

    @Test
    void factoryRejectsMissingCompensationPolicy() {
        assertThatThrownBy(() -> new RabbitPublishCompensationSettingsFactory(null))
            .isInstanceOf(NullPointerException.class)
            .hasMessage("compensationPolicy");
    }

    @Test
    void normalizesCompensationPropertiesThroughSharedPolicy() {
        properties.setPublishCompensationMaxAttempts(0);
        properties.setPublishCompensationBatchSize(0);
        properties.setPublishCompensationIntervalMs(0);
        properties.setPublishCompensationLeaseMs(0);
        RabbitPublishCompensationSettings settings =
            new RabbitPublishCompensationSettings(properties, compensationPolicy);
        LocalDateTime now = LocalDateTime.of(2026, 7, 7, 9, 30);

        assertThat(settings.maxAttempts()).isOne();
        assertThat(settings.batchSize()).isOne();
        assertThat(settings.nextAttempt(null)).isOne();
        assertThat(settings.nextAttempt(2)).isEqualTo(3);
        assertThat(settings.nextRetryAt(now)).isEqualTo(now.plusSeconds(1));
        assertThat(settings.expiredBefore(now)).isEqualTo(now.minusSeconds(1));
    }

    @Test
    void createsLeaseAwareClaim() {
        properties.setPublishCompensationLeaseMs(120000);
        properties.setPublishCompensationMaxAttempts(7);
        RabbitPublishCompensationSettings settings =
            new RabbitPublishCompensationSettingsFactory(compensationPolicy).create(properties);
        LocalDateTime claimedAt = LocalDateTime.of(2026, 7, 7, 11, 20);

        RabbitPublishClaim claim = settings.claim(claimedAt, "node-a");

        assertThat(claim.claimedAt()).isEqualTo(claimedAt);
        assertThat(claim.instanceId()).isEqualTo("node-a");
        assertThat(claim.expiredBefore()).isEqualTo(claimedAt.minusMinutes(2));
        assertThat(claim.maxAttempts()).isEqualTo(7);
    }
}
