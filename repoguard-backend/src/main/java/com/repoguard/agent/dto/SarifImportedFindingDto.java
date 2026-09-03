package com.repoguard.agent.dto;

public record SarifImportedFindingDto(
    String ruleId,
    String filePath,
    Integer lineNumber,
    String severity,
    String message
) {
}
