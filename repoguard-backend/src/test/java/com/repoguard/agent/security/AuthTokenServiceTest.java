package com.repoguard.agent.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.entity.UserAccount;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class AuthTokenServiceTest {

    private final AuthProperties authProperties = new AuthProperties();

    @Test
    void verifyReturnsAuthenticatedUserForValidToken() {
        authProperties.setTokenSecret("test-secret");
        AuthTokenService tokenService = new AuthTokenService(
            authProperties,
            Clock.fixed(Instant.parse("2026-06-11T00:00:00Z"), ZoneOffset.UTC)
        );

        String token = tokenService.issueAccessToken(user()).token();

        var authenticatedUser = tokenService.verify(token);
        assertThat(authenticatedUser).isPresent();
        assertThat(authenticatedUser.get().id()).isEqualTo(1001L);
        assertThat(authenticatedUser.get().username()).isEqualTo("admin");
        assertThat(authenticatedUser.get().role()).isEqualTo("ADMIN");
    }

    @Test
    void verifyRejectsTamperedToken() {
        authProperties.setTokenSecret("test-secret");
        AuthTokenService tokenService = new AuthTokenService(authProperties);

        String token = tokenService.issueAccessToken(user()).token() + "x";

        assertThat(tokenService.verify(token)).isEmpty();
    }

    @Test
    void verifyRejectsExpiredToken() {
        authProperties.setTokenSecret("test-secret");
        authProperties.setAccessTokenTtlSeconds(1);
        AuthTokenService issuingService = new AuthTokenService(
            authProperties,
            Clock.fixed(Instant.parse("2026-06-11T00:00:00Z"), ZoneOffset.UTC)
        );
        AuthTokenService verifyingService = new AuthTokenService(
            authProperties,
            Clock.fixed(Instant.parse("2026-06-11T00:00:02Z"), ZoneOffset.UTC)
        );

        String token = issuingService.issueAccessToken(user()).token();

        assertThat(verifyingService.verify(token)).isEmpty();
    }

    @Test
    void verifyRejectsTokenAtExactExpirySecond() {
        authProperties.setTokenSecret("test-secret");
        authProperties.setAccessTokenTtlSeconds(1);
        AuthTokenService issuingService = new AuthTokenService(
            authProperties,
            Clock.fixed(Instant.parse("2026-06-11T00:00:00Z"), ZoneOffset.UTC)
        );
        AuthTokenService verifyingService = new AuthTokenService(
            authProperties,
            Clock.fixed(Instant.parse("2026-06-11T00:00:01Z"), ZoneOffset.UTC)
        );

        String token = issuingService.issueAccessToken(user()).token();

        assertThat(verifyingService.verify(token)).isEmpty();
    }

    private UserAccount user() {
        UserAccount user = new UserAccount();
        user.setId(1001L);
        user.setUsername("admin");
        user.setRole("ADMIN");
        return user;
    }

    @Test
    void refreshTokenHashDoesNotExposeRawRefreshToken() {
        AuthTokenService tokenService = new AuthTokenService(authProperties);

        String refreshToken = tokenService.issueRefreshToken(false).token();
        String tokenHash = tokenService.hashRefreshToken(refreshToken);

        assertThat(tokenHash).isNotEqualTo(refreshToken);
        assertThat(tokenHash).hasSize(64);
    }
}
