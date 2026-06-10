package com.repoguard.agent.dto;

public record MessageQueueMetricDto(
    String label,
    String value,
    String note,
    String noteClass,
    String color
) {
}
