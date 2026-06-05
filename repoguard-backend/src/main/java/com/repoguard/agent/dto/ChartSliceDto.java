package com.repoguard.agent.dto;

/**
 * 仪表盘风险分布和规则命中图表共用的数据切片。
 */
public record ChartSliceDto(
    String name,
    long value,
    String color,
    String percent
) {
}
