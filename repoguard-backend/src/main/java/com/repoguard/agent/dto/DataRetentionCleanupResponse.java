package com.repoguard.agent.dto;

public record DataRetentionCleanupResponse(
    boolean executed,
    long cleanupBatchId,
    int retentionDays,
    int maxTasks,
    String backupReference,
    String cutoffTime,
    long candidateTasks,
    int selectedTasks,
    int deletedBatchItems,
    int deletedPublications,
    int deletedBatches,
    int deletedChangedFiles,
    int deletedTimelines,
    int deletedFindings,
    int deletedTasks
) {
}
