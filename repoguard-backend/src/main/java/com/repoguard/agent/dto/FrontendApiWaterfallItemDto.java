package com.repoguard.agent.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record FrontendApiWaterfallItemDto(
    @Size(max = 80)
    String operation,

    @Size(max = 512)
    String path,

    @Pattern(regexp = "GET|POST|PUT|PATCH|DELETE|HEAD|OPTIONS")
    String method,

    @Min(100)
    @Max(599)
    Integer status,

    @Size(max = 32)
    String result,

    @Size(max = 128)
    String traceId,

    @Min(0)
    @Max(104857600)
    Long responseBytes,

    @Min(0)
    @Max(86400000)
    Long startedAtMs,

    @Min(0)
    @Max(86400000)
    Long durationMs
) {
}
