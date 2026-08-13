package com.repoguard.agent.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.startsWith;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

class SchemaVersionGuardTest {

    private static final int EXPECTED_VERSION = 59;

    private final SchemaVersionGuard guard = new SchemaVersionGuard(null, EXPECTED_VERSION);

    @Test
    void rejectsBootWhenHistoryTableIsAbsent() {
        assertThatThrownBy(() -> guard.verify(false, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("flyway_schema_history is absent")
            .hasMessageContaining("migration owner");
    }

    @Test
    void doesNotQueryMigrationRowsWhenHistoryTableIsAbsent() {
        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.queryForObject(
            startsWith("select count(*)"),
            eq(Integer.class),
            eq("flyway_schema_history")
        )).thenReturn(0);
        SchemaVersionGuard databaseGuard = new SchemaVersionGuard(jdbcTemplate, EXPECTED_VERSION);

        assertThatThrownBy(databaseGuard::afterPropertiesSet)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("flyway_schema_history is absent");
        verify(jdbcTemplate, never()).queryForObject(
            startsWith("select max("),
            eq(Integer.class)
        );
    }

    @Test
    void rejectsBootWhenNoSuccessfulMigrationIsRecorded() {
        assertThatThrownBy(() -> guard.verify(true, null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No successful Flyway migration");
    }

    @Test
    void rejectsBootAgainstAStaleSchema() {
        assertThatThrownBy(() -> guard.verify(true, EXPECTED_VERSION - 1))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("older than the required " + EXPECTED_VERSION);
    }

    @Test
    void allowsBootWhenSchemaMatchesExpectation() {
        assertThatCode(() -> guard.verify(true, EXPECTED_VERSION)).doesNotThrowAnyException();
    }

    @Test
    void allowsBootWhenSchemaIsAheadOfExpectation() {
        assertThatCode(() -> guard.verify(true, EXPECTED_VERSION + 1)).doesNotThrowAnyException();
    }
}
