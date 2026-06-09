package com.repoguard.agent.dto;

public record ReviewRuleMetricDto(
    String label,
    String value,
    String note,
    String color
) {
}
