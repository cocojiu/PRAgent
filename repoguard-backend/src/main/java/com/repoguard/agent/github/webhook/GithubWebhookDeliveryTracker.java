package com.repoguard.agent.github.webhook;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/** Keeps the latest redacted delivery outcome for the setup wizard. It never stores payloads or secrets. */
@Component
public class GithubWebhookDeliveryTracker {

    private final Clock clock;
    private final AtomicReference<Delivery> latest = new AtomicReference<>();

    public GithubWebhookDeliveryTracker() {
        this(Clock.systemUTC());
    }

    GithubWebhookDeliveryTracker(Clock clock) {
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public void record(String deliveryId, String event, String repository, String status) {
        latest.set(new Delivery(
            redact(deliveryId),
            event == null ? "unknown" : event.trim().toLowerCase(java.util.Locale.ROOT),
            repository == null ? "unknown" : repository.trim().toLowerCase(java.util.Locale.ROOT),
            status == null ? "unknown" : status,
            clock.instant()
        ));
    }

    public Delivery latestFor(String organization, String repository) {
        Delivery value = latest.get();
        if (value == null || !matches(value.repository(), organization, repository)) {
            return null;
        }
        return value;
    }

    private boolean matches(String fullName, String organization, String repository) {
        if (!StringUtils.hasText(organization) || !StringUtils.hasText(repository)) {
            return false;
        }
        return fullName.equalsIgnoreCase(organization.trim() + "/" + repository.trim());
    }

    private String redact(String deliveryId) {
        if (!StringUtils.hasText(deliveryId)) {
            return null;
        }
        String normalized = deliveryId.trim();
        return normalized.length() <= 8 ? "****" : normalized.substring(0, 4) + "…" + normalized.substring(normalized.length() - 4);
    }

    public record Delivery(String deliveryId, String event, String repository, String status, Instant receivedAt) {
    }
}
