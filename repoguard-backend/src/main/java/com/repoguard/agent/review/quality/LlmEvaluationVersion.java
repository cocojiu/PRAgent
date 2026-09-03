package com.repoguard.agent.review.quality;

import com.repoguard.agent.review.LlmReviewVersions;
import com.repoguard.agent.review.ServerRiskAggregator;
import java.math.BigDecimal;
import org.springframework.util.StringUtils;

/** Immutable version tuple used to compare provider evaluation results. */
public record LlmEvaluationVersion(
    String provider,
    String model,
    String promptVersion,
    String contextVersion,
    String schemaVersion,
    String chunkPolicyVersion,
    BigDecimal temperature,
    String ruleVersion,
    String codeRevision,
    String verifierVersion,
    String aggregationVersion
) {

    public LlmEvaluationVersion(
        String provider,
        String model,
        String promptVersion,
        String contextVersion,
        String schemaVersion,
        String chunkPolicyVersion
    ) {
        this(
            provider,
            model,
            promptVersion,
            contextVersion,
            schemaVersion,
            chunkPolicyVersion,
            null,
            "unspecified",
            "unspecified",
            LlmReviewVersions.VERIFIER,
            ServerRiskAggregator.VERSION
        );
    }

    /** Compatibility constructor for callers that already supplied temperature/rule/commit. */
    public LlmEvaluationVersion(
        String provider,
        String model,
        String promptVersion,
        String contextVersion,
        String schemaVersion,
        String chunkPolicyVersion,
        BigDecimal temperature,
        String ruleVersion,
        String codeRevision
    ) {
        this(
            provider,
            model,
            promptVersion,
            contextVersion,
            schemaVersion,
            chunkPolicyVersion,
            temperature,
            ruleVersion,
            codeRevision,
            LlmReviewVersions.VERIFIER,
            ServerRiskAggregator.VERSION
        );
    }

    public LlmEvaluationVersion {
        provider = requireText(provider, "provider");
        model = requireText(model, "model");
        promptVersion = requireText(promptVersion, "promptVersion");
        contextVersion = requireText(contextVersion, "contextVersion");
        schemaVersion = requireText(schemaVersion, "schemaVersion");
        chunkPolicyVersion = requireText(chunkPolicyVersion, "chunkPolicyVersion");
        if (temperature != null
            && (temperature.compareTo(BigDecimal.ZERO) < 0
                || temperature.compareTo(BigDecimal.valueOf(2)) > 0)) {
            throw new IllegalArgumentException("LLM evaluation temperature must be between 0 and 2");
        }
        temperature = temperature == null ? null : temperature.stripTrailingZeros();
        ruleVersion = requireText(ruleVersion, "ruleVersion");
        codeRevision = requireText(codeRevision, "codeRevision");
        verifierVersion = requireText(verifierVersion, "verifierVersion");
        aggregationVersion = requireText(aggregationVersion, "aggregationVersion");
    }

    public String versionKey() {
        return provider + "/" + model
            + "|prompt=" + promptVersion
            + "|context=" + contextVersion
            + "|schema=" + schemaVersion
            + "|chunk=" + chunkPolicyVersion
            + "|verifier=" + verifierVersion
            + "|aggregation=" + aggregationVersion
            + "|temperature=" + (temperature == null ? "unspecified" : temperature.toPlainString())
            + "|rules=" + ruleVersion
            + "|commit=" + codeRevision;
    }

    /**
     * Returns whether this version contains enough immutable inputs to compare live runs.
     * The six-field compatibility constructor intentionally remains usable for offline tests,
     * but those runs must not be promoted as a real-data baseline.
     */
    public boolean reproducible() {
        return temperature != null
            && !isPlaceholder(ruleVersion)
            && !isPlaceholder(codeRevision)
            && !isPlaceholder(verifierVersion)
            && !isPlaceholder(aggregationVersion);
    }

    private boolean isPlaceholder(String value) {
        return "unspecified".equalsIgnoreCase(value) || "unknown".equalsIgnoreCase(value)
            || "legacy-unknown".equalsIgnoreCase(value);
    }

    private static String requireText(String value, String field) {
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("LLM evaluation " + field + " must not be blank");
        }
        return value.trim();
    }
}
