package com.repoguard.agent.dto;

/**
 * 仪表盘趋势图中的评审数量节点。
 */
public record ReviewTrendPointDto(
    String date,
    long value
) {
}
