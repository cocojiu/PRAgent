package com.repoguard.agent.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.entity.UserAccount;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class AuthTokenServiceTest {

    private static final Instant NOW = Instant.parse("2026-06-11T00:00:00Z");
    private static final Clock FIXED_CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final long FUTURE_EXPIRY = NOW.getEpochSecond() + 900;
    private static final Path SERVICE_SOURCE =
        Path.of("src/main/java/com/repoguard/agent/security/AuthTokenService.java");

    private final AuthProperties authProperties = new AuthProperties();

    @Test
    void verifyReturnsAuthenticatedUserForValidToken() {
        authProperties.setTokenSecret("test-secret");
        AuthTokenService tokenService = new AuthTokenService(authProperties, FIXED_CLOCK);

        String token = tokenService.issueAccessToken(user()).token();

        var authenticatedUser = tokenService.verify(token);
        assertThat(authenticatedUser).isPresent();
        assertThat(authenticatedUser.get().id()).isEqualTo(1001L);
        assertThat(authenticatedUser.get().username()).isEqualTo("admin");
        assertThat(authenticatedUser.get().role()).isEqualTo("ADMIN");
        assertThat(authenticatedUser.get().sessionVersion()).isEqualTo(7);
    }

    @Test
    void issuedTokenCarriesPayloadVersionAndActiveKeyId() {
        authProperties.setTokenSecret("test-secret");
        authProperties.setTokenSecretId("k7");
        AuthTokenService tokenService = new AuthTokenService(authProperties, FIXED_CLOCK);

        String payload = decodedPayload(tokenService.issueAccessToken(user()).token());

        assertThat(payload).startsWith("v2:k7:1001:");
        assertThat(payload).doesNotContain("admin");
        assertThat(payload.split(":", -1)).hasSize(7);
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

    @Test
    void verifyRejectsLegacyFourFieldTokenEvenWhenCorrectlySigned() {
        authProperties.setTokenSecret("test-secret");
        AuthTokenService tokenService = new AuthTokenService(authProperties, FIXED_CLOCK);

        String token = signedToken("1001:admin:ADMIN:" + FUTURE_EXPIRY, "test-secret");

        assertThat(tokenService.verify(token)).isEmpty();
    }

    @Test
    void verifyAcceptsLegacyFiveFieldTokenAndKeepsItsSessionVersion() {
        authProperties.setTokenSecret("test-secret");
        AuthTokenService tokenService = new AuthTokenService(authProperties, FIXED_CLOCK);

        String token = signedToken("1001:admin:ADMIN:7:" + FUTURE_EXPIRY, "test-secret");

        var authenticatedUser = tokenService.verify(token);
        assertThat(authenticatedUser).isPresent();
        assertThat(authenticatedUser.get().username()).isEqualTo("admin");
        assertThat(authenticatedUser.get().sessionVersion()).isEqualTo(7);
    }

    @Test
    void verifyRejectsLegacyTokenWhoseUsernameCarriesTheFieldDelimiter() {
        authProperties.setTokenSecret("test-secret");
        AuthTokenService tokenService = new AuthTokenService(authProperties, FIXED_CLOCK);

        String token = signedToken("1001:ad:min:ADMIN:7:" + FUTURE_EXPIRY, "test-secret");

        assertThat(tokenService.verify(token)).isEmpty();
    }

    @Test
    void verifyRejectsVersionedPayloadWithMissingFields() {
        authProperties.setTokenSecret("test-secret");
        AuthTokenService tokenService = new AuthTokenService(authProperties, FIXED_CLOCK);

        String token = signedToken("v2:k1:1001:YWRtaW4:QURNSU4:" + FUTURE_EXPIRY, "test-secret");

        assertThat(tokenService.verify(token)).isEmpty();
    }

    @Test
    void verifyAcceptsTokenSignedWithPreviousSecretAfterRotation() {
        authProperties.setTokenSecret("first-secret");
        authProperties.setTokenSecretId("k1");
        AuthTokenService tokenService = new AuthTokenService(authProperties, FIXED_CLOCK);
        String token = tokenService.issueAccessToken(user()).token();

        authProperties.setTokenSecret("second-secret");
        authProperties.setTokenSecretId("k2");
        authProperties.setTokenSecretPrevious("first-secret");
        authProperties.setTokenSecretPreviousId("k1");

        var authenticatedUser = tokenService.verify(token);
        assertThat(authenticatedUser).isPresent();
        assertThat(authenticatedUser.get().id()).isEqualTo(1001L);
        assertThat(decodedPayload(tokenService.issueAccessToken(user()).token())).startsWith("v2:k2:");
    }

    @Test
    void verifyRejectsPreviousKeyIdTokenOncePreviousSecretIsRetired() {
        authProperties.setTokenSecret("first-secret");
        authProperties.setTokenSecretId("k1");
        AuthTokenService tokenService = new AuthTokenService(authProperties, FIXED_CLOCK);
        String token = tokenService.issueAccessToken(user()).token();

        authProperties.setTokenSecret("second-secret");
        authProperties.setTokenSecretId("k2");

        assertThat(tokenService.verify(token)).isEmpty();
    }

    @Test
    void verifyRejectsTokenWhoseKeyIdWasRepointedAtAnotherKey() {
        authProperties.setTokenSecret("second-secret");
        authProperties.setTokenSecretId("k2");
        authProperties.setTokenSecretPrevious("first-secret");
        authProperties.setTokenSecretPreviousId("k1");
        AuthTokenService tokenService = new AuthTokenService(authProperties, FIXED_CLOCK);

        String token = tokenService.issueAccessToken(user()).token();
        String signature = token.substring(token.indexOf('.') + 1);
        String repointedToken = encodedToken(decodedPayload(token).replaceFirst("^v2:k2:", "v2:k1:"), signature);

        assertThat(tokenService.verify(token)).isPresent();
        assertThat(tokenService.verify(repointedToken)).isEmpty();
    }

    @Test
    void verifyRejectsTokenWithUnknownKeyId() {
        authProperties.setTokenSecret("test-secret");
        authProperties.setTokenSecretId("k1");
        AuthTokenService tokenService = new AuthTokenService(authProperties, FIXED_CLOCK);

        String token = signedToken(
            "v2:k9:1001:YWRtaW4:QURNSU4:7:" + FUTURE_EXPIRY,
            "test-secret"
        );

        assertThat(tokenService.verify(token)).isEmpty();
    }

    @Test
    void verifyRejectsVersionedTokenSignedWithAnUnrelatedSecret() {
        authProperties.setTokenSecret("test-secret");
        authProperties.setTokenSecretId("k1");
        AuthTokenService tokenService = new AuthTokenService(authProperties, FIXED_CLOCK);

        String token = signedToken(
            "v2:k1:1001:YWRtaW4:QURNSU4:7:" + FUTURE_EXPIRY,
            "attacker-secret"
        );

        assertThat(tokenService.verify(token)).isEmpty();
    }

    @Test
    void verifyRoundTripsFreeTextIdentityFieldsWithoutDelimiterAssumptions() {
        authProperties.setTokenSecret("test-secret");
        AuthTokenService tokenService = new AuthTokenService(authProperties, FIXED_CLOCK);

        for (String username : new String[] {"ad:min", "::::", "a.b-c_d", "运维:admin", "admin/plus+slash"}) {
            String token = tokenService.issueAccessToken(1001L, username, "ROLE:ADMIN", 7).token();

            var authenticatedUser = tokenService.verify(token);
            assertThat(authenticatedUser).as("username %s", username).isPresent();
            assertThat(authenticatedUser.get().username()).isEqualTo(username);
            assertThat(authenticatedUser.get().role()).isEqualTo("ROLE:ADMIN");
            assertThat(authenticatedUser.get().sessionVersion()).isEqualTo(7);
        }
    }

    @Test
    void signatureComparisonUsesConstantTimeEquality() throws IOException {
        String source = Files.readString(SERVICE_SOURCE, StandardCharsets.UTF_8);

        assertThat(source).contains("MessageDigest.isEqual(");
        assertThat(source).doesNotContain("signature.equals(");
        assertThat(source).doesNotContain(".equals(signature)");
        assertThat(source).doesNotContain("== signature");
    }

    @Test
    void verifyRejectsSignatureThatOnlyDiffersInLength() {
        authProperties.setTokenSecret("test-secret");
        AuthTokenService tokenService = new AuthTokenService(authProperties, FIXED_CLOCK);

        String token = tokenService.issueAccessToken(user()).token();
        String truncated = token.substring(0, token.length() - 1);

        assertThat(tokenService.verify(truncated)).isEmpty();
    }

    private UserAccount user() {
        UserAccount user = new UserAccount();
        user.setId(1001L);
        user.setUsername("admin");
        user.setRole("ADMIN");
        user.setSessionVersion(7);
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

    private String signedToken(String payload, String secret) {
        String encodedPayload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encodedPayload + "." + hmacSha256(encodedPayload, secret);
    }

    private String encodedToken(String payload, String signature) {
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(payload.getBytes(StandardCharsets.UTF_8)) + "." + signature;
    }

    private String decodedPayload(String token) {
        return new String(
            Base64.getUrlDecoder().decode(token.substring(0, token.indexOf('.'))),
            StandardCharsets.UTF_8
        );
    }

    private String hmacSha256(String value, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Test signing is not available", ex);
        }
    }
}
