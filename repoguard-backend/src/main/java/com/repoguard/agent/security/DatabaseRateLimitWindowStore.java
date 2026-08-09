package com.repoguard.agent.security;

import com.repoguard.agent.config.ApiRuntimeEnabled;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
@ApiRuntimeEnabled
@ConditionalOnProperty(name = "app.security.rate-limit-store", havingValue = "database")
public class DatabaseRateLimitWindowStore {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final byte[] KEY_DOMAIN = "repoguard:shared-rate-limit:v1\0"
        .getBytes(StandardCharsets.UTF_8);
    private static final long RETAINED_WINDOWS = 2L;

    private final JdbcTemplate jdbcTemplate;
    private final SecretKeySpec keySecret;
    private final AtomicLong lastCleanupMinute = new AtomicLong(Long.MIN_VALUE);

    public DatabaseRateLimitWindowStore(JdbcTemplate jdbcTemplate, AuthProperties authProperties) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.keySecret = new SecretKeySpec(
            Objects.requireNonNull(authProperties, "authProperties must not be null")
                .getTokenSecret()
                .getBytes(StandardCharsets.UTF_8),
            HMAC_ALGORITHM
        );
    }

    @Transactional
    public boolean tryAcquire(String scope, String key, long windowEpochMinute, int limit) {
        if (limit <= 0) {
            return false;
        }
        String normalizedScope = requireScope(scope);
        byte[] bucketKey = bucketKey(normalizedScope, Objects.requireNonNull(key, "key must not be null"));
        jdbcTemplate.update(
            """
            INSERT INTO api_rate_limit_window (
                rate_limit_scope,
                bucket_key,
                window_epoch_minute,
                request_count,
                created_at,
                updated_at
            ) VALUES (?, ?, ?, LAST_INSERT_ID(1), CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
            ON DUPLICATE KEY UPDATE
                request_count = LAST_INSERT_ID(request_count + 1),
                updated_at = CURRENT_TIMESTAMP
            """,
            normalizedScope,
            bucketKey,
            windowEpochMinute
        );
        Long count = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        cleanupExpiredWindows(windowEpochMinute);
        return count != null && count <= limit;
    }

    private void cleanupExpiredWindows(long currentMinute) {
        long previousCleanup = lastCleanupMinute.get();
        if (previousCleanup >= currentMinute || !lastCleanupMinute.compareAndSet(previousCleanup, currentMinute)) {
            return;
        }
        jdbcTemplate.update(
            "DELETE FROM api_rate_limit_window WHERE window_epoch_minute < ?",
            currentMinute - RETAINED_WINDOWS
        );
    }

    private String requireScope(String scope) {
        String normalized = Objects.requireNonNull(scope, "scope must not be null").trim();
        if (normalized.isEmpty() || normalized.length() > 64) {
            throw new IllegalArgumentException("scope length must be between 1 and 64 characters");
        }
        return normalized;
    }

    private byte[] bucketKey(String scope, String key) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(keySecret);
            mac.update(KEY_DOMAIN);
            mac.update(scope.getBytes(StandardCharsets.UTF_8));
            mac.update((byte) 0);
            return mac.doFinal(key.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("Unable to derive shared rate-limit key", ex);
        }
    }
}
