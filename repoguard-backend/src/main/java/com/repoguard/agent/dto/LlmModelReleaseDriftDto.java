package com.repoguard.agent.dto;

import java.time.LocalDateTime;
import java.util.List;

/** Tenant-scoped, privacy-safe release drift evidence and deterministic repair intent. */
public record LlmModelReleaseDriftDto(
    LocalDateTime checkedAt,
    Boolean healthy,
    String fingerprint,
    List<FindingDto> findings,
    ReleaseSummary releaseSummary,
    AssignmentSummary assignmentSummary
) {

    public record FindingDto(
        String code,
        String severity,
        String resourceType,
        Long resourceId,
        String resourceKey,
        String observedValue,
        String desiredValue,
        Boolean repairable
    ) { }

    public record ReleaseSummary(
        Integer activeCount,
        Integer canaryCount,
        List<Long> activeReleaseIds,
        List<Long> canaryReleaseIds,
        Long desiredActiveReleaseId,
        Long desiredCanaryReleaseId
    ) { }

    public record AssignmentSummary(
        Integer assignedTaskCount,
        Integer missingReleaseCount,
        Integer metadataMismatchCount,
        Integer runningTaskDriftCount,
        List<AssignmentDto> samples
    ) { }

    public record AssignmentDto(
        Long taskId,
        String releaseKey,
        String provider,
        String model,
        String taskStatus,
        Boolean started,
        String issueCode,
        Boolean repairable
    ) { }
}
