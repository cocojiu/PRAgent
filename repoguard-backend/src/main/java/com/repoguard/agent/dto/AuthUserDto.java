package com.repoguard.agent.dto;

public record AuthUserDto(
    Long id,
    String username,
    String email,
    String role
) {
}
