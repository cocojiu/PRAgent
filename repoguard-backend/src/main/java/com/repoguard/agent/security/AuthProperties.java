package com.repoguard.agent.security;

import java.util.Arrays;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "repoguard.auth")
public class AuthProperties {

    private static final String DEFAULT_TOKEN_SECRET = "repoguard-local-dev-auth-token-secret";

    private String tokenSecret = "repoguard-local-dev-auth-token-secret";
    private long tokenTtlSeconds = 7200;
    private long rememberTokenTtlSeconds = 2592000;

    public String getTokenSecret() {
        return tokenSecret;
    }

    public void setTokenSecret(String tokenSecret) {
        this.tokenSecret = tokenSecret;
    }

    public long getTokenTtlSeconds() {
        return tokenTtlSeconds;
    }

    public void setTokenTtlSeconds(long tokenTtlSeconds) {
        this.tokenTtlSeconds = tokenTtlSeconds;
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
