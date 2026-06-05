package com.repoguard.agent.dto;

/**
 * 仪表盘展示的运行时依赖健康状态项。
 */
public record SystemHealthItemDto(
    String name,
    String status
) {
}
