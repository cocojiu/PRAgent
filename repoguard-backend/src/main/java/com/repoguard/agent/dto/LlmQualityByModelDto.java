package com.repoguard.agent.dto;

public record LlmQualityByModelDto(
    String model,
    long taskCount,
    String averageDuration,
    String averageTokens,
    String averageCost,
    String parseSuccessRate,
    String fallbackRate,
    String partialFallbackRate,
    String validRate,
    String falsePositiveRate
) {
}
