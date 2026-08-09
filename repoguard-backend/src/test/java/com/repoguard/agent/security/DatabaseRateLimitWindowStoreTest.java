package com.repoguard.agent.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class DatabaseRateLimitWindowStoreTest {

    @Test
    void storesOnlyFixedLengthKeyDigestAndEnforcesReturnedGlobalCount() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AuthProperties authProperties = new AuthProperties();
        authProperties.setTokenSecret("0123456789abcdef0123456789abcdef");
        DatabaseRateLimitWindowStore store = new DatabaseRateLimitWindowStore(jdbcTemplate, authProperties);
        when(jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class)).thenReturn(3L);

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
    }

    @Test
    void rejectsInvalidScopeBeforeWriting() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        AuthProperties authProperties = new AuthProperties();
        authProperties.setTokenSecret("0123456789abcdef0123456789abcdef");
        DatabaseRateLimitWindowStore store = new DatabaseRateLimitWindowStore(jdbcTemplate, authProperties);

        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            store.tryAcquire(" ", "client", 1L, 1)
        ).isInstanceOf(IllegalArgumentException.class);

        verify(jdbcTemplate, org.mockito.Mockito.never()).update(any(String.class), any(Object[].class));
    }
}
