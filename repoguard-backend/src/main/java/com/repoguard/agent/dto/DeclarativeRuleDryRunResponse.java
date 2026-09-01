package com.repoguard.agent.dto;

import java.util.List;

public record DeclarativeRuleDryRunResponse(
    String ruleId,
    Long taskId,
    int matchedFiles,
    int matchedLines,
    List<DeclarativeRuleMatchDto> matches
) {
}
