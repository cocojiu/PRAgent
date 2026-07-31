package com.repoguard.agent.config;

import java.util.Locale;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "repoguard.review.human-review")
public class ReviewHumanReviewProperties {

    private static final Set<String> SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");

    private String minimumSeverity = "HIGH";
    private boolean requireBlocking = true;
    private String minimumConfidence = "HIGH";

    public String getMinimumSeverity() {
        return minimumSeverity;
    }

    public void setMinimumSeverity(String minimumSeverity) {
        this.minimumSeverity = normalize(minimumSeverity, SEVERITIES, "minimumSeverity");
    }

    public boolean isRequireBlocking() {
        return requireBlocking;
    }

    public void setRequireBlocking(boolean requireBlocking) {
        this.requireBlocking = requireBlocking;
    }

    public String getMinimumConfidence() {
        return minimumConfidence;
    }

    public void setMinimumConfidence(String minimumConfidence) {
        this.minimumConfidence = normalize(
            minimumConfidence,
            Set.of("LOW", "MEDIUM", "HIGH"),
            "minimumConfidence"
        );
    }

    private String normalize(String value, Set<String> allowed, String field) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!allowed.contains(normalized)) {
            throw new IllegalArgumentException("Unsupported human review " + field + ": " + value);
        }
        return normalized;
    }
}
