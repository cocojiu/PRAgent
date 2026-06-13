package com.repoguard.agent.dto;

public record LlmQualityByRepositoryDto(
    String repository,
    long taskCount,
    String fallbackRate,
    String validRate,
    String falsePositiveRate
) {
}
