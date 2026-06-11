package com.repoguard.agent.security;

import com.repoguard.agent.entity.UserAccount;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class AuthTokenService {

    private final AuthProperties authProperties;
    private final Clock clock;

    @Autowired
    public AuthTokenService(AuthProperties authProperties) {
        this(authProperties, Clock.systemUTC());
    }

    AuthTokenService(AuthProperties authProperties, Clock clock) {
        this.authProperties = authProperties;
        this.clock = clock;
    }

    public TokenIssue issue(UserAccount user, boolean remember) {
        long ttlSeconds = remember ? authProperties.getRememberTokenTtlSeconds() : authProperties.getTokenTtlSeconds();
        long expiresAt = Instant.now(clock).plusSeconds(ttlSeconds).getEpochSecond();
        String payload = user.getId() + ":" + user.getUsername() + ":" + user.getRole() + ":" + expiresAt;
        String encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        String signature = sign(encodedPayload);
        return new TokenIssue(encodedPayload + "." + signature, ttlSeconds);
    }

    public Optional<AuthenticatedUser> verify(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        String[] tokenParts = token.split("\\.");
        if (tokenParts.length != 2 || !secureEquals(sign(tokenParts[0]), tokenParts[1])) {
            return Optional.empty();
        }
        String payload;
        try {
            payload = new String(Base64.getUrlDecoder().decode(tokenParts[0]), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException ex) {
            return Optional.empty();
        }
        String[] payloadParts = payload.split(":", 4);
        if (payloadParts.length != 4) {
            return Optional.empty();
        }
        long userId;
        long expiresAt;
        try {
            userId = Long.parseLong(payloadParts[0]);
            expiresAt = Long.parseLong(payloadParts[3]);
        } catch (NumberFormatException ex) {
            return Optional.empty();
        }
        if (Instant.now(clock).getEpochSecond() > expiresAt) {
            return Optional.empty();
        }
        return Optional.of(new AuthenticatedUser(userId, payloadParts[1], payloadParts[2], expiresAt));
    }

    private String sign(String encodedPayload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(authProperties.getTokenSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(encodedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Auth token signing is not available", ex);
        }
    }

    private boolean secureEquals(String expected, String actual) {
        return java.security.MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            actual.getBytes(StandardCharsets.UTF_8)
        );
    }

    public record TokenIssue(String token, long expiresInSeconds) {
    }

    public record AuthenticatedUser(Long id, String username, String role, long expiresAt) {
    }
}
