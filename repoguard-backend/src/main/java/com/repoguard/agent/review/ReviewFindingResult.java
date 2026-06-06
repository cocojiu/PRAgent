package com.repoguard.agent.review;

public record ReviewFindingResult(
    String severity,
    String source,
    String ruleId,
    String filePath,
    Integer lineNumber,
    String message,
    String recommendation
) {
}
