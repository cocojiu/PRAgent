package com.repoguard.agent.dto;

public record AuthResponse(
    String accessToken,
    String refreshToken,
    String tokenType,
    Long accessTokenExpiresInSeconds,
    Long refreshTokenExpiresInSeconds,
    AuthUserDto user
) {
}
