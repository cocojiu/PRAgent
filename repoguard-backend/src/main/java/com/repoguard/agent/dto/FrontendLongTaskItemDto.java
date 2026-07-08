package com.repoguard.agent.dto;

public record FrontendLongTaskItemDto(
    Long startedAtMs,
    Long durationMs,
    String region,
    String operation,
    Integer itemCount,
    Integer totalCount,
    String apiPath,
    String apiTraceId,
    Long apiResponseBytes,
    Long apiDurationMs
) {
}
