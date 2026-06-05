package com.repoguard.agent.dto;

/**
 * 仪表盘顶部指标卡片数据。
 */
public record DashboardMetricDto(
    String label,
    String value,
    String trend,
    String trendType,
    String color
) {
}
