package com.repoguard.agent.dto;

public record FrontendApiWaterfallItemDto(
    String operation,
    String path,
    String method,
    Integer status,
    String result,
    String traceId,
    Long responseBytes,
    Long startedAtMs,
    Long durationMs
) {
}
