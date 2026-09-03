package com.repoguard.agent.dto;

public record GithubChecksDiagnosticDto(
    String code,
    String label,
    String status,
    String message,
    boolean blocking
) {
}
