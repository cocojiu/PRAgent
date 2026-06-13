package com.repoguard.agent.dto;

public record LlmQualityByModelDto(
    String model,
    long taskCount,
    String averageDuration,
    String parseSuccessRate,
    String fallbackRate,
    String validRate,
    String falsePositiveRate
) {
}
