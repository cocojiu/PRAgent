package com.repoguard.agent.review;

import org.springframework.util.StringUtils;

public record ReviewStrategyRelease(
    long snapshotId,
    long strategyVersion,
    String promptVersion,
    String contextVersion,
    String schemaVersion,
    String verifierVersion,
    String aggregationVersion,
    EnforcementMode enforcementMode,
    boolean replayVerified
) {

    public ReviewStrategyRelease {
        if (snapshotId < 0 || strategyVersion < 1) {
            throw new IllegalArgumentException("Review strategy versions must be positive");
        }
        promptVersion = requireText(promptVersion, "promptVersion");
        contextVersion = requireText(contextVersion, "contextVersion");
        schemaVersion = requireText(schemaVersion, "schemaVersion");
        verifierVersion = requireText(verifierVersion, "verifierVersion");
        aggregationVersion = requireText(aggregationVersion, "aggregationVersion");
        if (enforcementMode == null) {
            throw new IllegalArgumentException("Review strategy enforcementMode must not be null");
        }
    }

    public static ReviewStrategyRelease observeDefaults() {
        return new ReviewStrategyRelease(
            0,
            1,
            LlmReviewVersions.PROMPT,
            LlmReviewVersions.CONTEXT,
            LlmReviewVersions.SCHEMA,
            LlmReviewVersions.VERIFIER,
            ServerRiskAggregator.VERSION,
            EnforcementMode.OBSERVE,
            false
        );
    }

    static ReviewStrategyRelease legacyRuntimeDefaults() {
        return new ReviewStrategyRelease(
            1,
            1,
            LlmReviewVersions.PROMPT,
            LlmReviewVersions.CONTEXT,
            LlmReviewVersions.SCHEMA,
            LlmReviewVersions.VERIFIER,
            ServerRiskAggregator.VERSION,
            EnforcementMode.BLOCK,
            true
        );
    }

    public boolean supportsRuntimeVersions() {
        return LlmReviewVersions.PROMPT.equals(promptVersion)
            && LlmReviewVersions.CONTEXT.equals(contextVersion)
            && LlmReviewVersions.SCHEMA.equals(schemaVersion)
            && LlmReviewVersions.VERIFIER.equals(verifierVersion)
            && ServerRiskAggregator.VERSION.equals(aggregationVersion);
    }

    private static String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Review strategy " + field + " must not be blank");
        }
        return value.trim();
    }
}
