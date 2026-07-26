package com.repoguard.agent.dto;

/**
 * 当前筛选条件下的评审任务聚合指标，耗时以秒为单位由前端格式化。
 */
public record ReviewTaskListSummary(
    long total,
    long highRisk,
    long failed,
    long averageDurationSeconds
) {
}
