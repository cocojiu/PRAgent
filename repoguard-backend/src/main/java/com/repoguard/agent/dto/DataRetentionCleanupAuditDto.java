package com.repoguard.agent.dto;

public record DataRetentionCleanupAuditDto(
    Long id,
    String mode,
    String status,
    Integer retentionDays,
    Integer maxTasks,
    String backupReference,
    String cutoffTime,
    Long candidateTasks,
    Integer selectedTasks,
    Integer deletedBatchItems,
    Integer deletedPublications,
    Integer deletedBatches,
    Integer deletedChangedFiles,
    Integer deletedTimelines,
    Integer deletedFindings,
    Integer deletedTasks,
    String failureReason,
    String failureMessage,
    String createdAt,
    String completedAt,
    String updatedAt
) {
}
