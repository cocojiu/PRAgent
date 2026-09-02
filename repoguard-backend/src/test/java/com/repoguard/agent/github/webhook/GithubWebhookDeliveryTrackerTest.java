package com.repoguard.agent.github.webhook;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class GithubWebhookDeliveryTrackerTest {

    @Test
    void storesOnlyRedactedLatestDeliveryForMatchingRepository() {
        GithubWebhookDeliveryTracker tracker = new GithubWebhookDeliveryTracker(
            Clock.fixed(Instant.parse("2026-09-03T01:00:00Z"), ZoneOffset.UTC)
        );

        tracker.record("delivery-123456", "check_run", "octo/repo", "accepted_skipped");

        GithubWebhookDeliveryTracker.Delivery delivery = tracker.latestFor("octo", "repo");
        assertThat(delivery).isNotNull();
        assertThat(delivery.deliveryId()).isEqualTo("deli…3456");
        assertThat(delivery.repository()).isEqualTo("octo/repo");
        assertThat(delivery.receivedAt()).isEqualTo(Instant.parse("2026-09-03T01:00:00Z"));
        assertThat(tracker.latestFor("other", "repo")).isNull();
    }
}
