package com.repoguard.agent.dto;

public record SecretReEncryptionItemDto(
    String tableName,
    Long recordId,
    String fieldName,
    String provider,
    String sourceFormat,
    String sourceKeyId,
    String targetKeyId,
    String status,
    String failureReason,
    String message
) {
}
