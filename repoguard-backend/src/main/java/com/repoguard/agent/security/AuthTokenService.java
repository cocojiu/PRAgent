package com.repoguard.agent.security;

import com.repoguard.agent.authentication.AuthenticatedPrincipal;
import com.repoguard.agent.entity.UserAccount;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AuthTokenService {

    private static final String PAYLOAD_VERSION = "v2";
    private static final int VERSIONED_PAYLOAD_FIELDS = 7;
    private static final int LEGACY_PAYLOAD_FIELDS = 5;

    private final AuthProperties authProperties;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    public AuthTokenService(AuthProperties authProperties) {
        this(authProperties, Clock.systemUTC());
    }

    AuthTokenService(AuthProperties authProperties, Clock clock) {
        this.authProperties = authProperties;
        this.clock = clock;
    }

    public TokenIssue issueAccessToken(UserAccount user) {
        return issueAccessToken(
            user.getId(),
            user.getUsername(),
            user.getRole(),
            safeSessionVersion(user)
        );
    }

    public TokenIssue issueAccessToken(Long userId, String username, String role, int sessionVersion) {
        long ttlSeconds = authProperties.getAccessTokenTtlSeconds();
        long expiresAt = Instant.now(clock).plusSeconds(ttlSeconds).getEpochSecond();
        String payload = PAYLOAD_VERSION + ":"
            + authProperties.getTokenSecretId() + ":"
            + userId + ":"
            + encodeField(username) + ":"
            + encodeField(role) + ":"
            + sessionVersion + ":"
            + expiresAt;
        String encodedPayload = encode(payload.getBytes(StandardCharsets.UTF_8));
        String signature = sign(encodedPayload, authProperties.getTokenSecret());
        return new TokenIssue(encodedPayload + "." + signature, ttlSeconds);
    }

    public TokenIssue issueRefreshToken(boolean remember) {
        long ttlSeconds = refreshTokenTtlSeconds(remember);
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return new TokenIssue(encode(bytes), ttlSeconds);
    }

    public long refreshTokenTtlSeconds(boolean remember) {
        return remember ? authProperties.getRememberTokenTtlSeconds() : authProperties.getRefreshTokenTtlSeconds();
    }

    public String hashRefreshToken(String refreshToken) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(refreshToken.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Refresh token hashing is not available", ex);
        }
    }

    public Optional<AuthenticatedPrincipal> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String[] tokenParts = token.split("\\.");
        if (tokenParts.length != 2) {
            return Optional.empty();
        }
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(tokenParts[0]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        String[] payloadParts = payload.split(":", -1);
        if (payloadParts.length == VERSIONED_PAYLOAD_FIELDS && PAYLOAD_VERSION.equals(payloadParts[0])) {
            return verifyVersionedPayload(tokenParts[0], tokenParts[1], payloadParts);
        }
        if (payloadParts.length == LEGACY_PAYLOAD_FIELDS) {
            return verifyLegacyPayload(tokenParts[0], tokenParts[1], payloadParts);
        }
        return Optional.empty();
    }

    private Optional<AuthenticatedPrincipal> verifyVersionedPayload(
        String encodedPayload,
        String signature,
        String[] payloadParts
    ) {
        String secret = secretForKeyId(payloadParts[1]);
        if (secret == null || !signatureMatches(encodedPayload, signature, secret)) {
            return Optional.empty();
        }
        String username = decodeField(payloadParts[3]);
        String role = decodeField(payloadParts[4]);
        if (username == null || role == null) {
            return Optional.empty();
        }
        return principal(payloadParts[2], username, role, payloadParts[5], payloadParts[6]);
    }

    private Optional<AuthenticatedPrincipal> verifyLegacyPayload(
        String encodedPayload,
        String signature,
        String[] payloadParts
    ) {
        if (!signatureMatches(encodedPayload, signature, authProperties.getTokenSecret())
            && !signatureMatchesPreviousSecret(encodedPayload, signature)) {
            return Optional.empty();
        }
        return principal(payloadParts[0], payloadParts[1], payloadParts[2], payloadParts[3], payloadParts[4]);
    }

    private Optional<AuthenticatedPrincipal> principal(
        String userId,
        String username,
        String role,
        String sessionVersion,
        String expiresAt
    ) {
        long parsedUserId;
        int parsedSessionVersion;
        long parsedExpiresAt;
        try {
            parsedUserId = Long.parseLong(userId);
            parsedSessionVersion = Integer.parseInt(sessionVersion);
            parsedExpiresAt = Long.parseLong(expiresAt);
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
        if (Instant.now(clock).getEpochSecond() >= parsedExpiresAt) {
            return Optional.empty();
        }
        return Optional.of(new AuthenticatedPrincipal(
            parsedUserId,
            username,
            role,
            parsedExpiresAt,
            parsedSessionVersion
        ));
    }

    private String secretForKeyId(String keyId) {
        if (keyId.equals(authProperties.getTokenSecretId())) {
            return authProperties.getTokenSecret();
        }
        String previousKeyId = authProperties.getTokenSecretPreviousId();
        String previousSecret = authProperties.getTokenSecretPrevious();
        if (previousKeyId != null
            && !previousKeyId.isBlank()
            && keyId.equals(previousKeyId)
            && previousSecret != null
            && !previousSecret.isBlank()) {
            return previousSecret;
        }
        return null;
    }

    private boolean signatureMatchesPreviousSecret(String encodedPayload, String signature) {
        String previousSecret = authProperties.getTokenSecretPrevious();
        return previousSecret != null
            && !previousSecret.isBlank()
            && signatureMatches(encodedPayload, signature, previousSecret);
    }

    private int safeSessionVersion(UserAccount user) {
        return user.getSessionVersion() == null ? 0 : user.getSessionVersion();
    }

    private String encodeField(String value) {
        return encode((value == null ? "" : value).getBytes(StandardCharsets.UTF_8));
    }

    private String decodeField(String value) {
        try {
            return new String(Base64.getUrlDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private String sign(String encodedPayload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return encode(mac.doFinal(encodedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Auth token signing is not available", ex);
        }
    }

    private boolean signatureMatches(String encodedPayload, String signature, String secret) {
        return MessageDigest.isEqual(
            sign(encodedPayload, secret).getBytes(StandardCharsets.UTF_8),
            signature.getBytes(StandardCharsets.UTF_8)
        );
    }

    public record TokenIssue(String token, long expiresInSeconds) {
    }
}
