package com.repoguard.agent.review;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Immutable, validated repository policy loaded from a versioned .repoguard.yml file. */
public record RepositoryPolicyDocument(
    int schemaVersion,
    List<String> includePatterns,
    List<String> excludePatterns,
    Map<String, RuleOverride> rules,
    LlmOverride llm,
    PublicationOverride publication,
    List<SuppressionReference> suppressions
) {

    public RepositoryPolicyDocument {
        includePatterns = includePatterns == null ? List.of() : List.copyOf(includePatterns);
        excludePatterns = excludePatterns == null ? List.of() : List.copyOf(excludePatterns);
        rules = rules == null ? Map.of() : Map.copyOf(rules);
        suppressions = suppressions == null ? List.of() : List.copyOf(suppressions);
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("repository policy schemaVersion must be positive");
        }
    }

    public static RepositoryPolicyDocument empty() {
        return new RepositoryPolicyDocument(1, List.of(), List.of(), Map.of(), null, null, List.of());
    }

    public record RuleOverride(Boolean enabled, String severity, EnforcementMode enforcementMode) {
    }

    public record LlmOverride(Boolean enabled, Integer tokenBudget, BigDecimal costBudget) {
    }

    public record PublicationOverride(String commentMode, String checkMode) {
    }

    public record SuppressionReference(
        String ruleId,
        String fileGlob,
        String symbol,
        String reason,
        OffsetDateTime expiresAt
    ) {
    }
}
