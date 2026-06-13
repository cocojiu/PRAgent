package com.repoguard.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record FindingFeedbackRequest(
    @NotBlank @Pattern(regexp = "(?i)valid|false_positive|fixed|ignored|unreviewed") String status,
    @Size(max = 1024) String note
) {
}
