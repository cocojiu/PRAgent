package com.repoguard.agent.review.quality;

/** Publishes a deduplicated, aggregate-only release alert through the existing notification outbox. */
public interface LlmModelReleaseAlertPublisher {

    void publish(LlmModelReleaseMetricSnapshot snapshot);
}
