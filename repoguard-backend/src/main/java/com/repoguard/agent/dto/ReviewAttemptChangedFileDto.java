package com.repoguard.agent.dto;

public record ReviewAttemptChangedFileDto(
    Long id,
    String path,
    String changeType,
    Integer additions,
    Integer deletions
) {
}
