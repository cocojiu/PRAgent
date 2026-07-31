package com.repoguard.agent.dto;

import java.util.List;

public record ReviewRulesResponse(
    List<ReviewRuleMetricDto> metrics,
    List<ReviewRuleConfigDto> rules,
    List<ReviewQualityGroupDto> qualityGroups,
    ReviewStrategyPolicyDto strategyPolicy
) {

    public ReviewRulesResponse(List<ReviewRuleMetricDto> metrics, List<ReviewRuleConfigDto> rules) {
        this(metrics, rules, List.of(), null);
    }
}
