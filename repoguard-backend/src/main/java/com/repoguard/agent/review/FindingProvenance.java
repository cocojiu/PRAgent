package com.repoguard.agent.review;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import org.springframework.util.StringUtils;

public record FindingProvenance(
    String detectorVersion,
    long ruleConfigVersion,
    long rulePolicyVersion,
    String originalSeverity,
    String originalConfidence
) {

    private static final String NOT_APPLICABLE = "not-applicable";

    public FindingProvenance {
        detectorVersion = textOrDefault(detectorVersion, "legacy-detector-v1");
        ruleConfigVersion = Math.max(1L, ruleConfigVersion);
        rulePolicyVersion = Math.max(1L, rulePolicyVersion);
        originalSeverity = textOrDefault(originalSeverity, "INFO").toUpperCase(Locale.ROOT);
        originalConfidence = textOrDefault(originalConfidence, "LOW").toUpperCase(Locale.ROOT);
    }

    public static FindingProvenance legacy(String source, String ruleId, String severity, String confidence) {
        String detector = StringUtils.hasText(ruleId)
            ? ruleId.trim().toLowerCase() + "-legacy-v1"
            : "LLM".equalsIgnoreCase(source) ? "llm-review-v2" : "legacy-detector-v1";
        return new FindingProvenance(detector, 1, 1, severity, confidence);
    }

    public static FindingProvenance rule(ReviewRuleSettings settings) {
        return new FindingProvenance(
            settings.detectorVersion(),
            settings.configVersion(),
            settings.policyVersion(),
            settings.severity(),
            settings.confidenceLabel()
        );
    }

    public static FindingProvenance llm(String severity, String confidence) {
        return new FindingProvenance("llm-review-v2", 1, 1, severity, confidence);
    }

    public FindingProvenance merge(FindingProvenance other) {
        if (other == null) {
            return this;
        }
        return new FindingProvenance(
            mergeVersions(detectorVersion, other.detectorVersion),
            Math.max(ruleConfigVersion, other.ruleConfigVersion),
            Math.max(rulePolicyVersion, other.rulePolicyVersion),
            strongerSeverity(originalSeverity, other.originalSeverity),
            strongerConfidence(originalConfidence, other.originalConfidence)
        );
    }

    private static String mergeVersions(String first, String second) {
        Set<String> versions = new LinkedHashSet<>();
        addVersion(versions, first);
        addVersion(versions, second);
        return versions.isEmpty() ? NOT_APPLICABLE : String.join("+", versions);
    }

    private static void addVersion(Set<String> versions, String value) {
        if (!StringUtils.hasText(value)) {
            return;
        }
        for (String part : value.split("\\+")) {
            if (!part.isBlank()) {
                versions.add(part.trim());
            }
        }
    }

    private static String strongerSeverity(String first, String second) {
        return severityRank(first) >= severityRank(second) ? first : second;
    }

    private static int severityRank(String value) {
        return switch (textOrDefault(value, "INFO").toUpperCase(Locale.ROOT)) {
            case "CRITICAL" -> 5;
            case "HIGH" -> 4;
            case "MEDIUM" -> 3;
            case "LOW" -> 2;
            default -> 1;
        };
    }

    private static String strongerConfidence(String first, String second) {
        return confidenceRank(first) >= confidenceRank(second) ? first : second;
    }

    private static int confidenceRank(String value) {
        return switch (textOrDefault(value, "LOW").toUpperCase(Locale.ROOT)) {
            case "HIGH" -> 3;
            case "MEDIUM" -> 2;
            default -> 1;
        };
    }

    private static String textOrDefault(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }
}
