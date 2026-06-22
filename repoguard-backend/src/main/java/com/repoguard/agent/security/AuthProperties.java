package com.repoguard.agent.security;

import java.util.Arrays;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "repoguard.auth")
public class AuthProperties {

    private static final String DEFAULT_TOKEN_SECRET = "changeme-local-dev";

    private String tokenSecret = DEFAULT_TOKEN_SECRET;
    private long accessTokenTtlSeconds = 900;
    private long refreshTokenTtlSeconds = 7200;
    private long rememberTokenTtlSeconds = 2592000;

    public String getTokenSecret() {
        return tokenSecret;
    }

    public void setTokenSecret(String tokenSecret) {
        this.tokenSecret = tokenSecret;
    }

    public long getAccessTokenTtlSeconds() {
        return accessTokenTtlSeconds;
    }

    public void setAccessTokenTtlSeconds(long accessTokenTtlSeconds) {
        this.accessTokenTtlSeconds = accessTokenTtlSeconds;
    }

    public long getRefreshTokenTtlSeconds() {
        return refreshTokenTtlSeconds;
    }

    public void setRefreshTokenTtlSeconds(long refreshTokenTtlSeconds) {
        this.refreshTokenTtlSeconds = refreshTokenTtlSeconds;
    }

    public long getRememberTokenTtlSeconds() {
        return rememberTokenTtlSeconds;
    }

    public void setRememberTokenTtlSeconds(long rememberTokenTtlSeconds) {
        this.rememberTokenTtlSeconds = rememberTokenTtlSeconds;
    }

    public void validateForProfiles(String[] activeProfiles) {
        boolean productionProfile = Arrays.stream(activeProfiles)
            .anyMatch(profile -> "prod".equalsIgnoreCase(profile));
        if (productionProfile && (tokenSecret == null || tokenSecret.isBlank() || DEFAULT_TOKEN_SECRET.equals(tokenSecret))) {
            throw new IllegalStateException("repoguard.auth.token-secret must be configured in prod profile");
        }
    }
}
