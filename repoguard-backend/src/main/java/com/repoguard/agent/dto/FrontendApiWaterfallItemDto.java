package com.repoguard.agent.dto;

public record FrontendApiWaterfallItemDto(
    String operation,
    String method,
    Integer status,
    String result,
    Long startedAtMs,
    Long durationMs
) {
}
