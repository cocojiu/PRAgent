package com.repoguard.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record HumanReviewRequest(
    @NotBlank @Pattern(regexp = "(?i)approve|changes_requested|reject") String action,
    @Size(max = 1024) String note
) {
}
