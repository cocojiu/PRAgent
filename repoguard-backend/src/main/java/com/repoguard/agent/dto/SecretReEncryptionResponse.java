package com.repoguard.agent.dto;

import java.util.List;

public record SecretReEncryptionResponse(
    boolean executed,
    int scannedCount,
    int reEncryptedCount,
    int skippedCount,
    int failedCount,
    List<SecretReEncryptionItemDto> items
) {
}
