package com.repoguard.agent.dto;

/**
 * 仪表盘规则命中表格中的统计项。
 */
public record FailedRuleStatDto(
    String name,
    long count,
    String trend,
    String direction,
    String percent
) {
}
