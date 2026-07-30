package com.repoguard.agent.review;

import org.springframework.util.StringUtils;

public record ReviewExecutionProvenance(
    long strategyPolicyVersion,
    String promptVersion,
    String contextVersion,
    String schemaVersion,
    String verifierVersion,
    String aggregationVersion
) {

    public ReviewExecutionProvenance {
        strategyPolicyVersion = Math.max(1L, strategyPolicyVersion);
        promptVersion = textOrDefault(promptVersion, "not-applicable");
        contextVersion = textOrDefault(contextVersion, "not-applicable");
        schemaVersion = textOrDefault(schemaVersion, "not-applicable");
        verifierVersion = textOrDefault(verifierVersion, "not-applicable");
        aggregationVersion = textOrDefault(aggregationVersion, ServerRiskAggregator.VERSION);
    }

    public static ReviewExecutionProvenance rulesOnly() {
        return new ReviewExecutionProvenance(
            1,
            "not-applicable",
            "not-applicable",
            "not-applicable",
            "not-applicable",
            ServerRiskAggregator.VERSION
        );
    }

    public static ReviewExecutionProvenance from(ReviewStrategyRelease release) {
        ReviewStrategyRelease safe = release == null ? ReviewStrategyRelease.observeDefaults() : release;
        return new ReviewExecutionProvenance(
            safe.snapshotId() > 0 ? safe.snapshotId() : 1,
            safe.promptVersion(),
            safe.contextVersion(),
            safe.schemaVersion(),
            safe.verifierVersion(),
            safe.aggregationVersion()
        );
    }

    private static String textOrDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}
