package com.repoguard.agent.dto;

public record AuthResponse(
    String token,
    String tokenType,
    Long expiresInSeconds,
    AuthUserDto user
) {
}
