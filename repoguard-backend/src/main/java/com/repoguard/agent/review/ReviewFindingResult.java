package com.repoguard.agent.review;

public record ReviewFindingResult(
    String severity,
    String source,
    String ruleId,
    String filePath,
    Integer lineNumber,
    String message,
    String recommendation,
    String confidence,
    String evidence,
    String impact,
    String fixExample,
    boolean isBlocking,
    String reviewDimension,
    String enforcementMode,
    String policyReason
) {
    public ReviewFindingResult(
        String severity,
        String source,
        String ruleId,
        String filePath,
        Integer lineNumber,
        String message,
        String recommendation,
        String confidence,
        String evidence,
        String impact,
        String fixExample,
        boolean isBlocking,
        String reviewDimension
    ) {
        this(
            severity,
            source,
            ruleId,
            filePath,
            lineNumber,
            message,
            recommendation,
            confidence,
            evidence,
            impact,
            fixExample,
            isBlocking,
            reviewDimension,
            isBlocking ? EnforcementMode.BLOCK.name() : EnforcementMode.COMMENT.name(),
            "legacy_finding"
        );
    }

    public ReviewFindingResult(
        String severity,
        String source,
        String ruleId,
        String filePath,
        Integer lineNumber,
        String message,
        String recommendation
    ) {
        this(
            severity,
            source,
            ruleId,
            filePath,
            lineNumber,
            message,
            recommendation,
            defaultConfidence(severity),
            "",
            "",
            recommendation,
            false,
            defaultReviewDimension(source, ruleId),
            EnforcementMode.COMMENT.name(),
            "default_non_blocking"
        );
    }

    private static String defaultConfidence(String severity) {
        if (severity == null) {
            return "LOW";
        }
        return switch (severity.toUpperCase()) {
            case "CRITICAL", "HIGH" -> "HIGH";
            case "MEDIUM" -> "MEDIUM";
            default -> "LOW";
        };
    }

    private static String defaultReviewDimension(String source, String ruleId) {
        if ("RULE".equalsIgnoreCase(source) && ruleId != null && !ruleId.isBlank()) {
            return "PROJECT_RULE";
        }
        if ("LLM".equalsIgnoreCase(source)) {
            return "LLM";
        }
        return source == null || source.isBlank() ? "UNKNOWN" : source;
    }
}
