package com.repoguard.agent.identity;

/**
 * Persistence-neutral result of creating an authenticated identity session.
 */
public record IdentitySessionTokens(
    String accessToken,
    String refreshToken,
    String tokenType,
    long accessTokenExpiresInSeconds,
    long refreshTokenExpiresInSeconds,
    IdentityAccount account
) {
}
