package com.repoguard.agent.dto;

public record LlmQualityByRepositoryDto(
    String repository,
    long taskCount,
    String fallbackRate,
    String partialFallbackRate,
    String validRate,
    String falsePositiveRate
) {
}
