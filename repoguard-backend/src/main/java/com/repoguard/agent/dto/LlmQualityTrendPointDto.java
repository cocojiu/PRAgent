package com.repoguard.agent.dto;

public record LlmQualityTrendPointDto(
    String date,
    long taskCount,
    String parseSuccessRate,
    String fallbackRate,
    String partialFallbackRate
) {
}
