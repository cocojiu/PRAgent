package com.repoguard.agent.security;

import com.repoguard.agent.config.ApiRuntimeEnabled;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.sql.SQLException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;

@Component
@ApiRuntimeEnabled
@ConditionalOnProperty(name = "app.security.rate-limit-store", havingValue = "database")
public class DatabaseRateLimitWindowStore {

    private static final Logger LOGGER = LoggerFactory.getLogger(DatabaseRateLimitWindowStore.class);
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final byte[] KEY_DOMAIN = "repoguard:shared-rate-limit:v1\0"
        .getBytes(StandardCharsets.UTF_8);
    private static final long RETAINED_WINDOWS = 2L;
    private static final String ACQUIRE_DURATION = "repoguard.security.shared_rate_limit.acquire.duration";
    private static final String DATABASE_DURATION =
        "repoguard.security.shared_rate_limit.database.operation.duration";
    private static final String DATABASE_FAILURES =
        "repoguard.security.shared_rate_limit.database.failures";
    private static final String FAIL_CLOSED = "repoguard.security.shared_rate_limit.fail_closed";
    private static final String CLEANUP_DELETED_ROWS =
        "repoguard.security.shared_rate_limit.cleanup.deleted_rows";

    private final JdbcTemplate jdbcTemplate;
    private final MeterRegistry meterRegistry;
    private final TransactionOperations transactionOperations;
    private final SecretKeySpec keySecret;
    private final AtomicLong lastCleanupMinute = new AtomicLong(Long.MIN_VALUE);
    private final AtomicLong lastFailureLogMinute = new AtomicLong(Long.MIN_VALUE);

    @Autowired
    public DatabaseRateLimitWindowStore(
        JdbcTemplate jdbcTemplate,
        AuthProperties authProperties,
        MeterRegistry meterRegistry,
        PlatformTransactionManager transactionManager
    ) {
        this(
            jdbcTemplate,
            authProperties,
            meterRegistry,
            new TransactionTemplate(Objects.requireNonNull(transactionManager, "transactionManager must not be null"))
        );
    }

    DatabaseRateLimitWindowStore(
        JdbcTemplate jdbcTemplate,
        AuthProperties authProperties,
        MeterRegistry meterRegistry
    ) {
        this(jdbcTemplate, authProperties, meterRegistry, TransactionOperations.withoutTransaction());
    }

    DatabaseRateLimitWindowStore(
        JdbcTemplate jdbcTemplate,
        AuthProperties authProperties,
        MeterRegistry meterRegistry,
        TransactionOperations transactionOperations
    ) {
        this.jdbcTemplate = Objects.requireNonNull(jdbcTemplate, "jdbcTemplate must not be null");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry must not be null");
        this.transactionOperations = Objects.requireNonNull(
            transactionOperations,
            "transactionOperations must not be null"
        );
        this.keySecret = new SecretKeySpec(
            Objects.requireNonNull(authProperties, "authProperties must not be null")
                .getTokenSecret()
                .getBytes(StandardCharsets.UTF_8),
            HMAC_ALGORITHM
        );
    }

    public boolean tryAcquire(String scope, String key, long windowEpochMinute, int limit) {
        if (limit <= 0) {
            return false;
        }
        String normalizedScope = requireScope(scope);
        byte[] bucketKey = bucketKey(normalizedScope, Objects.requireNonNull(key, "key must not be null"));
        String metricScope = metricScope(normalizedScope);
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "fail_closed";
        try {
            Long count = transactionOperations.execute(status -> {
                Long acquiredCount = observeDatabaseOperation("acquire", () -> incrementAndRead(
                    normalizedScope,
                    bucketKey,
                    windowEpochMinute
                ));
                cleanupExpiredWindows(windowEpochMinute);
                return acquiredCount;
            });
            if (count == null) {
                throw new IllegalStateException("Shared rate-limit transaction returned no count");
            }
            boolean allowed = count <= limit;
            outcome = allowed ? "allowed" : "limited";
            return allowed;
        } catch (RuntimeException ex) {
            String operation = ex instanceof DatabaseOperationException databaseException
                ? databaseException.operation()
                : "transaction";
            recordDatabaseFailure(metricScope, operation, ex);
            return false;
        } finally {
            sample.stop(Timer.builder(ACQUIRE_DURATION)
                .description("Shared database rate-limit acquisition latency")
                .tags("scope", metricScope, "outcome", outcome)
                .register(meterRegistry));
        }
    }

    private Long incrementAndRead(String scope, byte[] bucketKey, long windowEpochMinute) {
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
            scope,
            bucketKey,
            windowEpochMinute
        );
        Long count = jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
        if (count == null) {
            throw new IllegalStateException("Shared rate-limit count query returned no value");
        }
        return count;
    }

    private void cleanupExpiredWindows(long currentMinute) {
        long previousCleanup = lastCleanupMinute.get();
        if (previousCleanup >= currentMinute || !lastCleanupMinute.compareAndSet(previousCleanup, currentMinute)) {
            return;
        }
        try {
            int deletedRows = observeDatabaseOperation(
                "cleanup",
                () -> jdbcTemplate.update(
                    "DELETE FROM api_rate_limit_window WHERE window_epoch_minute < ?",
                    currentMinute - RETAINED_WINDOWS
                )
            );
            meterRegistry.counter(CLEANUP_DELETED_ROWS).increment(deletedRows);
        } catch (RuntimeException ex) {
            lastCleanupMinute.compareAndSet(currentMinute, previousCleanup);
            throw ex;
        }
    }

    private <T> T observeDatabaseOperation(String operation, Supplier<T> supplier) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String outcome = "failure";
        try {
            T result = supplier.get();
            outcome = "success";
            return result;
        } catch (RuntimeException ex) {
            if (ex instanceof DatabaseOperationException) {
                throw ex;
            }
            throw new DatabaseOperationException(operation, ex);
        } finally {
            sample.stop(Timer.builder(DATABASE_DURATION)
                .description("Shared rate-limit database operation latency, including lock waits")
                .tags("operation", operation, "outcome", outcome)
                .register(meterRegistry));
        }
    }

    private void recordDatabaseFailure(String metricScope, String operation, RuntimeException ex) {
        String reason = failureReason(ex);
        meterRegistry.counter(DATABASE_FAILURES, "operation", operation, "reason", reason).increment();
        meterRegistry.counter(FAIL_CLOSED, "scope", metricScope, "operation", operation).increment();
        long currentMinute = System.currentTimeMillis() / 60_000L;
        long previousMinute = lastFailureLogMinute.get();
        if (previousMinute != currentMinute && lastFailureLogMinute.compareAndSet(previousMinute, currentMinute)) {
            LOGGER.error(
                "Shared rate-limit database operation failed; request rejected scope={} operation={} reason={}",
                metricScope,
                operation,
                reason,
                ex
            );
        }
    }

    private String failureReason(RuntimeException ex) {
        Throwable current = ex;
        while (current != null) {
            if (current instanceof SQLException sqlException) {
                if (sqlException.getErrorCode() == 1213 || "40001".equals(sqlException.getSQLState())) {
                    return "deadlock";
                }
                if (sqlException.getErrorCode() == 1205 || "41000".equals(sqlException.getSQLState())) {
                    return "lock_timeout";
                }
            }
            current = current.getCause();
        }
        return "database_error";
    }

    private String metricScope(String scope) {
        return switch (scope) {
            case "auth-ip",
                 "auth-account-ip",
                 "admin-api-key-ip",
                 "github-webhook-ip",
                 "github-webhook-repository" -> scope;
            default -> "other";
        };
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

    private static final class DatabaseOperationException extends RuntimeException {

        private static final long serialVersionUID = 1L;
        private final String operation;

        private DatabaseOperationException(String operation, RuntimeException cause) {
            super("Shared rate-limit database operation failed: " + operation, cause);
            this.operation = operation;
        }

        private String operation() {
            return operation;
        }
    }
}
