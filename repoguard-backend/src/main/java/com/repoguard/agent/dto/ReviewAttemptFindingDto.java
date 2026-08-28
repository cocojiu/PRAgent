package com.repoguard.agent.dto;

public record ReviewAttemptFindingDto(
    Long id,
    String category,
    String severity,
    String source,
    String ruleId,
    String file,
    Integer line,
    String message,
    String recommendation,
    String confidence,
    Boolean blocking,
    String feedbackStatus,
    String promptVersion,
    String contextVersion,
    String schemaVersion,
    String verifierVersion,
    String aggregationVersion
) {
}
