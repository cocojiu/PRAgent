package com.repoguard.agent.security;

import com.repoguard.agent.entity.UserAccount;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
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

    private String sign(String encodedPayload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(authProperties.getTokenSecret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(encodedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("Auth token signing is not available", ex);
        }
    }

    public record TokenIssue(String token, long expiresInSeconds) {
    }
}
