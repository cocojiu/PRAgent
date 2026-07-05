package com.repoguard.agent.dto;

public record FrontendLongTaskItemDto(
    Long startedAtMs,
    Long durationMs
) {
}
