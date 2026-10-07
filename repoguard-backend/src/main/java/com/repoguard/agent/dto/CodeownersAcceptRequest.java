package com.repoguard.agent.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;

public record CodeownersAcceptRequest(@NotNull @Positive Long userId, @NotNull @Positive Long attemptId,
    @NotNull @Pattern(regexp = "[a-fA-F0-9]{40}") String headSha,
    @NotNull @Pattern(regexp = "[a-fA-F0-9]{40}") String baseSha) { }
