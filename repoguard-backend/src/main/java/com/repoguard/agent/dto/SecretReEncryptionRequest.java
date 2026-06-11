package com.repoguard.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SecretReEncryptionRequest(
    @NotBlank
    String sourceEncryptionKey,
    @Size(max = 64)
    String sourceKeyId,
    @NotBlank
    String targetEncryptionKey,
    @NotBlank
    @Size(max = 64)
    String targetKeyId,
    Boolean execute,
    String confirmText
) {
}
