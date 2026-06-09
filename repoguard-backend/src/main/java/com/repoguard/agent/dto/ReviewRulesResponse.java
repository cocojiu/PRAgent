package com.repoguard.agent.dto;

import java.util.List;

public record ReviewRulesResponse(
    List<ReviewRuleMetricDto> metrics,
    List<ReviewRuleConfigDto> rules
) {
}
