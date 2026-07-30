package com.repoguard.agent.external;

import java.io.IOException;

public class ExternalHttpResponseTooLargeException extends IOException {

    private static final long serialVersionUID = 1L;

    private final ExternalHttpResponseProfile profile;
    private final long maxBytes;
    private final long observedBytes;
    private final String detection;

    public ExternalHttpResponseTooLargeException(
        String failureMessagePrefix,
        ExternalHttpResponseProfile profile,
        long maxBytes,
        long observedBytes,
        String detection
    ) {
        super(failureMessagePrefix
            + ": response body exceeds hard limit"
            + " profile=" + profile.metricTag()
            + " maxBytes=" + maxBytes
            + " observedBytes=" + observedBytes
            + " detection=" + detection);
        this.profile = profile;
        this.maxBytes = maxBytes;
        this.observedBytes = observedBytes;
        this.detection = detection;
    }

    public ExternalHttpResponseProfile getProfile() {
        return profile;
    }

    public long getMaxBytes() {
        return maxBytes;
    }

    public long getObservedBytes() {
        return observedBytes;
    }

    public String getDetection() {
        return detection;
    }
}
