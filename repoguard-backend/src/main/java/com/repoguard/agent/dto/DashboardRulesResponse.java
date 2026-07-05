package com.repoguard.agent.dto;

import java.util.List;

public record DashboardRulesResponse(
    List<ChartSliceDto> ruleHits,
    List<FailedRuleStatDto> failedRules
) {
}
