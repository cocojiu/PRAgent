package com.repoguard.agent.dto;

public record DeclarativeRuleMatchDto(
    String filePath,
    Integer lineNumber,
    String message,
    String evidence
) {
}
