package com.repoguard.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record SecretReEncryptionRequest(
    @NotBlank
    @Size(max = 4096)
    String sourceEncryptionKey,
    @Size(max = 64)
    String sourceKeyId,
    @NotBlank
    @Size(max = 4096)
    String targetEncryptionKey,
    @NotBlank
    @Size(max = 64)
    String targetKeyId,
    Boolean execute,
    @Size(max = 32)
    String confirmText
) {
}
