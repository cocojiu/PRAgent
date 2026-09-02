package com.repoguard.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Explicit confirmation of an exact drift preview; retries use the same operation key. */
public record LlmModelReleaseDriftRepairRequest(
    @NotBlank @Size(max = 128) String idempotencyKey,
    @NotBlank @Pattern(regexp = "(?i)[0-9a-f]{64}") String previewFingerprint,
    @NotNull Boolean confirm
) { }
