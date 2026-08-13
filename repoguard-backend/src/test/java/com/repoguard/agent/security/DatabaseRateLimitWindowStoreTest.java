package com.repoguard.agent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionOperations;

class DatabaseRateLimitWindowStoreTest {

    @Test
    void storesOnlyFixedLengthKeyDigestAndEnforcesReturnedGlobalCount() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AuthProperties authProperties = new AuthProperties();
        authProperties.setTokenSecret("0123456789abcdef0123456789abcdef");
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        DatabaseRateLimitWindowStore store = new DatabaseRateLimitWindowStore(
            jdbcTemplate,
            authProperties,
            meterRegistry
        );
        when(jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class)).thenReturn(3L);
        when(jdbcTemplate.update(
            "DELETE FROM api_rate_limit_window WHERE window_epoch_minute < ?",
            40L
        )).thenReturn(7);

        assertThat(store.tryAcquire("auth-ip", "login:203.0.113.10", 42L, 2)).isFalse();

        verify(jdbcTemplate).update(
            argThat(sql -> sql.contains("INSERT INTO api_rate_limit_window")),
            eq("auth-ip"),
            argThat((byte[] digest) -> digest.length == 32
                && !new String(digest, StandardCharsets.UTF_8).contains("203.0.113.10")),
            eq(42L)
        );
        verify(jdbcTemplate).update(
            "DELETE FROM api_rate_limit_window WHERE window_epoch_minute < ?",
            40L
        );
        assertThat(meterRegistry.get("repoguard.security.shared_rate_limit.acquire.duration")
            .tags("scope", "auth-ip", "outcome", "limited")
            .timer()
            .count()).isEqualTo(1L);
        assertThat(meterRegistry.get("repoguard.security.shared_rate_limit.database.operation.duration")
            .tags("operation", "acquire", "outcome", "success")
            .timer()
            .count()).isEqualTo(1L);
        assertThat(meterRegistry.get("repoguard.security.shared_rate_limit.cleanup.deleted_rows")
            .counter()
            .count()).isEqualTo(7.0d);
    }

    @Test
    void rejectsInvalidScopeBeforeWriting() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AuthProperties authProperties = new AuthProperties();
        authProperties.setTokenSecret("0123456789abcdef0123456789abcdef");
        DatabaseRateLimitWindowStore store = new DatabaseRateLimitWindowStore(
            jdbcTemplate,
            authProperties,
            new SimpleMeterRegistry()
        );

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            store.tryAcquire(" ", "client", 1L, 1)
        ).isInstanceOf(IllegalArgumentException.class);

        verify(jdbcTemplate, org.mockito.Mockito.never()).update(any(String.class), any(Object[].class));
    }

    @Test
    void rejectsFailClosedAndClassifiesLockTimeoutsWithoutHighCardinalityScopeTags() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AuthProperties authProperties = new AuthProperties();
        authProperties.setTokenSecret("0123456789abcdef0123456789abcdef");
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        DatabaseRateLimitWindowStore store = new DatabaseRateLimitWindowStore(
            jdbcTemplate,
            authProperties,
            meterRegistry
        );
        when(jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class)).thenThrow(
            new CannotAcquireLockException(
                "lock wait timed out",
                new SQLException("lock wait timed out", "41000", 1205)
            )
        );

        assertThat(store.tryAcquire("integration-dynamic-scope", "client", 42L, 10)).isFalse();

        assertThat(meterRegistry.get("repoguard.security.shared_rate_limit.acquire.duration")
            .tags("scope", "other", "outcome", "fail_closed")
            .timer()
            .count()).isEqualTo(1L);
        assertThat(meterRegistry.get("repoguard.security.shared_rate_limit.database.operation.duration")
            .tags("operation", "acquire", "outcome", "failure")
            .timer()
            .count()).isEqualTo(1L);
        assertThat(meterRegistry.get("repoguard.security.shared_rate_limit.database.failures")
            .tags("operation", "acquire", "reason", "lock_timeout")
            .counter()
            .count()).isEqualTo(1.0d);
        assertThat(meterRegistry.get("repoguard.security.shared_rate_limit.fail_closed")
            .tags("scope", "other", "operation", "acquire")
            .counter()
            .count()).isEqualTo(1.0d);
    }

    @Test
    void retriesCleanupAfterFailureAndRecordsDeletedRows() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AuthProperties authProperties = new AuthProperties();
        authProperties.setTokenSecret("0123456789abcdef0123456789abcdef");
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        DatabaseRateLimitWindowStore store = new DatabaseRateLimitWindowStore(
            jdbcTemplate,
            authProperties,
            meterRegistry
        );
        when(jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class)).thenReturn(1L);
        when(jdbcTemplate.update(
            "DELETE FROM api_rate_limit_window WHERE window_epoch_minute < ?",
            40L
        )).thenThrow(new CannotAcquireLockException(
            "deadlock",
            new SQLException("deadlock", "40001", 1213)
        )).thenReturn(2);

        assertThat(store.tryAcquire("auth-ip", "client-a", 42L, 10)).isFalse();
        assertThat(store.tryAcquire("auth-ip", "client-b", 42L, 10)).isTrue();

        verify(jdbcTemplate, times(2)).update(
            "DELETE FROM api_rate_limit_window WHERE window_epoch_minute < ?",
            40L
        );
        assertThat(meterRegistry.get("repoguard.security.shared_rate_limit.database.failures")
            .tags("operation", "cleanup", "reason", "deadlock")
            .counter()
            .count()).isEqualTo(1.0d);
        assertThat(meterRegistry.get("repoguard.security.shared_rate_limit.cleanup.deleted_rows")
            .counter()
            .count()).isEqualTo(2.0d);
    }

    @Test
    void rejectsFailClosedWhenTransactionCommitFails() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AuthProperties authProperties = new AuthProperties();
        authProperties.setTokenSecret("0123456789abcdef0123456789abcdef");
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        TransactionOperations transactionOperations = mock(TransactionOperations.class);
        DatabaseRateLimitWindowStore store = new DatabaseRateLimitWindowStore(
            jdbcTemplate,
            authProperties,
            meterRegistry,
            transactionOperations
        );
        when(transactionOperations.execute(any())).thenThrow(
            new TransactionSystemException("commit failed")
        );

        assertThat(store.tryAcquire("auth-ip", "client", 42L, 10)).isFalse();

        verifyNoInteractions(jdbcTemplate);
        assertThat(meterRegistry.get("repoguard.security.shared_rate_limit.database.failures")
            .tags("operation", "transaction", "reason", "database_error")
            .counter()
            .count()).isEqualTo(1.0d);
        assertThat(meterRegistry.get("repoguard.security.shared_rate_limit.fail_closed")
            .tags("scope", "auth-ip", "operation", "transaction")
            .counter()
            .count()).isEqualTo(1.0d);
    }
}
