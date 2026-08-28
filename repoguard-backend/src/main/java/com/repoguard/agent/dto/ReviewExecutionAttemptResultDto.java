package com.repoguard.agent.dto;

public record ReviewExecutionAttemptResultDto(
    ReviewExecutionAttemptDto attempt,
    PageResponse<ReviewAttemptChangedFileDto> changedFiles,
    PageResponse<ReviewAttemptFindingDto> findings
) {
}
