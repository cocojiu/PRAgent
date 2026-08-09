package com.repoguard.agent.review;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.security.AuthProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.Objects;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public final class ReviewTaskCursorCodec {

    private static final String VERSION = "v1";
    private static final String SEPARATOR = "|";
    private static final String HMAC_SHA256 = "HmacSHA256";

    private final AuthProperties authProperties;

    public ReviewTaskCursorCodec(AuthProperties authProperties) {
        this.authProperties = Objects.requireNonNull(authProperties, "authProperties must not be null");
    }

    public String encode(LocalDateTime createdAt, Long id, long total, String scope) {
        if (createdAt == null || id == null || id <= 0 || total < 0 || !StringUtils.hasText(scope)) {
            return null;
        }
        String payload = String.join(
            SEPARATOR,
            VERSION,
            authProperties.getTokenSecretId(),
            createdAt.toString(),
            id.toString(),
            Long.toString(total),
            scope
        );
        String encodedPayload = encode(payload.getBytes(StandardCharsets.UTF_8));
        return encodedPayload + "." + sign(encodedPayload, authProperties.getTokenSecret());
    }

    public Cursor decode(String encodedCursor, String expectedScope) {
        if (!StringUtils.hasText(encodedCursor)) {
            return null;
        }
        try {
            String[] tokenParts = encodedCursor.trim().split("\\.", -1);
            if (tokenParts.length != 2) {
                throw invalidCursor();
            }
            String payload = new String(
                Base64.getUrlDecoder().decode(tokenParts[0]),
                StandardCharsets.UTF_8
            );
            String[] parts = payload.split("\\|", -1);
            if (parts.length != 6 || !VERSION.equals(parts[0])) {
                throw invalidCursor();
            }
            String secret = secretForKeyId(parts[1]);
            if (secret == null || !signatureMatches(tokenParts[0], tokenParts[1], secret)) {
                throw invalidCursor();
            }
            LocalDateTime createdAt = LocalDateTime.parse(parts[2]);
            long id = Long.parseLong(parts[3]);
            long total = Long.parseLong(parts[4]);
            String scope = parts[5];
            if (id <= 0 || total < 0 || !secureEquals(scope, expectedScope)) {
                throw invalidCursor();
            }
            return new Cursor(createdAt, id, total);
        } catch (IllegalArgumentException | DateTimeParseException exception) {
            throw invalidCursor();
        }
    }

    private String secretForKeyId(String keyId) {
        if (Objects.equals(keyId, authProperties.getTokenSecretId())) {
            return authProperties.getTokenSecret();
        }
        if (Objects.equals(keyId, authProperties.getTokenSecretPreviousId())
            && StringUtils.hasText(authProperties.getTokenSecretPrevious())) {
            return authProperties.getTokenSecretPrevious();
        }
        return null;
    }

    private String sign(String encodedPayload, String secret) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            return encode(mac.doFinal(encodedPayload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new IllegalStateException("Review list cursor signing is not available", exception);
        }
    }

    private boolean signatureMatches(String encodedPayload, String signature, String secret) {
        return secureEquals(sign(encodedPayload, secret), signature);
    }

    private boolean secureEquals(String expected, String actual) {
        if (expected == null || actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
            expected.getBytes(StandardCharsets.UTF_8),
            actual.getBytes(StandardCharsets.UTF_8)
        );
    }

    private String encode(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    private static BusinessException invalidCursor() {
        return new BusinessException(ErrorCode.BAD_REQUEST, "Invalid review list cursor");
    }

    public record Cursor(LocalDateTime createdAt, Long id, long total) {
    }
}
