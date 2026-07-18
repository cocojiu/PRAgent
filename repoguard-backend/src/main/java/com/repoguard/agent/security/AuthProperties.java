package com.repoguard.agent.security;

import com.repoguard.agent.config.RuntimeProfilePolicy;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "repoguard.auth")
public class AuthProperties {

    private static final String DEFAULT_TOKEN_SECRET = "changeme-local-dev";
    private static final int MIN_PRODUCTION_TOKEN_SECRET_LENGTH = 32;

    private String tokenSecret = DEFAULT_TOKEN_SECRET;
    private long accessTokenTtlSeconds = 900;
    private long refreshTokenTtlSeconds = 7200;
    private long rememberTokenTtlSeconds = 2592000;
    private long refreshConcurrencyGraceSeconds = 5;
    private boolean registrationEnabled = true;
    private boolean secureCookies;
    private int publicAuthRequestsPerMinutePerIp = 30;
    private int publicAuthRequestsPerMinutePerAccountIp = 10;

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

    public long getRefreshConcurrencyGraceSeconds() {
        return refreshConcurrencyGraceSeconds;
    }

    public void setRefreshConcurrencyGraceSeconds(long refreshConcurrencyGraceSeconds) {
        this.refreshConcurrencyGraceSeconds = refreshConcurrencyGraceSeconds;
    }

    public boolean isRegistrationEnabled() {
        return registrationEnabled;
    }

    public void setRegistrationEnabled(boolean registrationEnabled) {
        this.registrationEnabled = registrationEnabled;
    }

    public boolean isSecureCookies() {
        return secureCookies;
    }

    public void setSecureCookies(boolean secureCookies) {
        this.secureCookies = secureCookies;
    }

    public int getPublicAuthRequestsPerMinutePerIp() {
        return publicAuthRequestsPerMinutePerIp;
    }

    public void setPublicAuthRequestsPerMinutePerIp(int value) {
        this.publicAuthRequestsPerMinutePerIp = value;
    }

    public int getPublicAuthRequestsPerMinutePerAccountIp() {
        return publicAuthRequestsPerMinutePerAccountIp;
    }

    public void setPublicAuthRequestsPerMinutePerAccountIp(int value) {
        this.publicAuthRequestsPerMinutePerAccountIp = value;
    }

    public void validateForProfiles(String[] activeProfiles) {
        requirePositive("repoguard.auth.access-token-ttl-seconds", accessTokenTtlSeconds);
        requirePositive("repoguard.auth.refresh-token-ttl-seconds", refreshTokenTtlSeconds);
        requirePositive("repoguard.auth.remember-token-ttl-seconds", rememberTokenTtlSeconds);
        requireNonNegative("repoguard.auth.refresh-concurrency-grace-seconds", refreshConcurrencyGraceSeconds);
        requirePositive("repoguard.auth.public-auth-requests-per-minute-per-ip", publicAuthRequestsPerMinutePerIp);
        requirePositive("repoguard.auth.public-auth-requests-per-minute-per-account-ip", publicAuthRequestsPerMinutePerAccountIp);

        boolean productionProfile = RuntimeProfilePolicy.isProductionLike(activeProfiles);
        if (!productionProfile) {
            return;
        }
        String normalizedTokenSecret = tokenSecret == null ? "" : tokenSecret.trim();
        if (normalizedTokenSecret.isBlank() || DEFAULT_TOKEN_SECRET.equals(normalizedTokenSecret)) {
            throw new IllegalStateException("repoguard.auth.token-secret must be configured in a production-like profile");
        }
        if (normalizedTokenSecret.length() < MIN_PRODUCTION_TOKEN_SECRET_LENGTH) {
            throw new IllegalStateException("repoguard.auth.token-secret must be at least 32 characters in a production-like profile");
        }
        if (!secureCookies) {
            throw new IllegalStateException("repoguard.auth.secure-cookies must be true in a production-like profile");
        }
    }

    private void requirePositive(String propertyName, long value) {
        if (value <= 0) {
            throw new IllegalStateException(propertyName + " must be greater than 0");
        }
    }

    private void requireNonNegative(String propertyName, long value) {
        if (value < 0) {
            throw new IllegalStateException(propertyName + " must be greater than or equal to 0");
        }
    }
}
