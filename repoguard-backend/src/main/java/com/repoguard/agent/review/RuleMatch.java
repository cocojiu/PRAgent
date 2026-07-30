package com.repoguard.agent.review;

public record RuleMatch(
    String ruleId,
    String filePath,
    Integer lineNumber,
    String message,
    String recommendation,
    String evidence,
    String impact,
    String reviewDimension,
    boolean evidenceVerified
) {
}
