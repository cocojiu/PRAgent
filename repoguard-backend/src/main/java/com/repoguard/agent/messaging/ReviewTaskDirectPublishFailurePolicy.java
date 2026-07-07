package com.repoguard.agent.messaging;

import java.util.Objects;

public record ReviewTaskDirectPublishFailurePolicy(
    long retryDelayMs,
    String timelinePrefix,
    boolean clearLlmQuality,
    boolean closeCurrentTimeline
) {
    private static final String DIRECT_PUBLISH_FAILED_PREFIX = "Message publish failed: ";
    private static final String MANUAL_REQUEUE_FAILED_PREFIX = "Message manual requeue failed: ";

    public ReviewTaskDirectPublishFailurePolicy {
        Objects.requireNonNull(timelinePrefix, "timelinePrefix");
    }

    public static ReviewTaskDirectPublishFailurePolicy directPublish(long retryDelayMs) {
        return new ReviewTaskDirectPublishFailurePolicy(
            retryDelayMs,
            DIRECT_PUBLISH_FAILED_PREFIX,
            true,
            false
        );
    }

    public static ReviewTaskDirectPublishFailurePolicy manualRequeue(long retryDelayMs) {
        return new ReviewTaskDirectPublishFailurePolicy(
            retryDelayMs,
            MANUAL_REQUEUE_FAILED_PREFIX,
            false,
            true
        );
    }

    public long normalizedRetryDelayMs() {
        return Math.max(1000, retryDelayMs);
    }
}
