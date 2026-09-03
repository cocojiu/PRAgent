package com.repoguard.agent.dto;

import java.time.LocalDateTime;

/** Result of a bounded, manually confirmed drift repair. */
public record LlmModelReleaseDriftRepairDto(
    String operationKey,
    String previewFingerprint,
    String status,
    Integer changedReleaseCount,
    Integer changedTaskCount,
    Integer skippedRunningTaskCount,
    String failureCode,
    LocalDateTime createdAt,
    LocalDateTime updatedAt,
    LlmModelReleaseDriftDto preview,
    LlmModelReleaseDriftDto after
) { }
