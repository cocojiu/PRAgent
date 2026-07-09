package com.repoguard.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;

public record FrontendLongTaskItemDto(
    @Min(0)
    @Max(86400000)
    Long startedAtMs,

    @Min(0)
    @Max(86400000)
    Long durationMs,

    @Size(max = 80)
    String region,

    @Size(max = 80)
    String operation,

    @Min(0)
    @Max(1000000)
    Integer itemCount,

    @Min(0)
    @Max(1000000)
    Integer totalCount,

    @Size(max = 512)
    String apiPath,

    @Size(max = 128)
    String apiTraceId,

    @Min(0)
    @Max(104857600)
    Long apiResponseBytes,

    @Min(0)
    @Max(86400000)
    Long apiDurationMs
) {
}
