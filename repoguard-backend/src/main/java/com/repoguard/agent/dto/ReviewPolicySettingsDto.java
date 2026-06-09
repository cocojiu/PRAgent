package com.repoguard.agent.dto;

public record ReviewPolicySettingsDto(
    Integer maxDiffLines,
    Integer llmTimeoutSeconds,
    Integer workerConcurrency,
    Boolean autoComment,
    Boolean autoRetry
) {
}
