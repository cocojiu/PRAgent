package com.repoguard.agent.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record AuthResponse(
    String accessToken,
    String refreshToken,
    String tokenType,
    Long accessTokenExpiresInSeconds,
    Long refreshTokenExpiresInSeconds,
    AuthUserDto user
) {
}
