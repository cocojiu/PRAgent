package com.repoguard.agent.dto;

public record SecretReEncryptionJobDto(
    Long id,
    boolean executed,
    String status,
    String sourceKeyId,
    String targetKeyId,
    String currentTable,
    long checkpointId,
    int batchSize,
    long scannedCount,
    long reEncryptedCount,
    long skippedCount,
    long failedCount,
    int retryCount,
    String nextRetryAt,
    String lastFailureReason,
    String createdByUsername,
    String createdAt,
    String updatedAt,
    String completedAt
) {
}
