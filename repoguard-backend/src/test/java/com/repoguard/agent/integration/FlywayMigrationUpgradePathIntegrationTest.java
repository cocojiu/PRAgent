package com.repoguard.agent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Exercises the supported rolling-upgrade path against a real MySQL instance.
 *
 * <p>The test is opt-in because local unit-test runs do not provision a database. CI enables it
 * with an isolated database and verifies both the V76 expand state and the V77 contract state.
 */
@EnabledIfEnvironmentVariable(named = "REPOGUARD_RUN_INTEGRATION_TESTS", matches = "true")
class FlywayMigrationUpgradePathIntegrationTest {

    private static final String INIT_SQL =
        "SET SESSION sql_mode = REPLACE(@@SESSION.sql_mode, 'ONLY_FULL_GROUP_BY', '')";
    private static final String COMMIT_SHA = "0123456789abcdef0123456789abcdef01234567";
    private static final String INPUT_FINGERPRINT =
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";

    @Test
    void migratesFromExpandToContractAndRejectsCrossTenantRelationships() throws Exception {
        String url = requiredEnvironment("SPRING_DATASOURCE_URL");
        String username = environmentOrDefault("SPRING_DATASOURCE_USERNAME", "root");
        String password = environmentOrDefault("SPRING_DATASOURCE_PASSWORD", "");
        Long tenantId = null;
        Long taskId = null;
        Long attemptId = null;
        String suffix = UUID.randomUUID().toString().replace("-", "");

        try {
            migrateTo(url, username, password, "76");
            try (Connection connection = open(url, username, password)) {
                assertThat(latestSuccessfulMigration(connection)).isEqualTo("76");
                assertThat(constraintExists(connection, "changed_file", "fk_changed_file_task")).isTrue();
                assertThat(constraintExists(connection, "changed_file", "fk_changed_file_tenant_task"))
                    .isFalse();

                tenantId = insertTenant(connection, suffix);
                taskId = insertReviewTask(connection, tenantId, suffix);
                attemptId = insertExecutionAttempt(connection, taskId);
                updateCurrentAttempt(connection, taskId, attemptId);
                insertChangedFile(connection, tenantId, taskId, attemptId, suffix);
            }

            migrateTo(url, username, password, "77");
            try (Connection connection = open(url, username, password)) {
                assertThat(latestSuccessfulMigration(connection)).isEqualTo("77");
                assertThat(constraintExists(connection, "changed_file", "fk_changed_file_task")).isFalse();
                assertThat(constraintExists(connection, "changed_file", "fk_changed_file_tenant_task"))
                    .isTrue();
                assertThat(rowCount(connection, "changed_file", tenantId, taskId)).isEqualTo(1L);

                long otherTenantId = insertTenant(connection, suffix + "-other");
                long finalOtherTenantId = otherTenantId;
                long finalTaskId = taskId;
                long finalAttemptId = attemptId;
                try {
                    assertThatThrownBy(() -> insertChangedFile(
                        connection,
                        finalOtherTenantId,
                        finalTaskId,
                        finalAttemptId,
                        suffix + "-cross"
                    )).isInstanceOf(SQLException.class);
                } finally {
                    deleteTenant(connection, otherTenantId);
                }
            }
        } finally {
            cleanup(url, username, password, tenantId, taskId, attemptId);
        }
    }

    private void migrateTo(String url, String username, String password, String target) {
        Flyway.configure()
            .dataSource(url, username, password)
            .locations("classpath:db/migration")
            .initSql(INIT_SQL)
            .target(target)
            .cleanDisabled(true)
            .load()
            .migrate();
    }

    private Connection open(String url, String username, String password) throws SQLException {
        return DriverManager.getConnection(url, username, password);
    }

    private Long insertTenant(Connection connection, String suffix) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "insert into tenant (tenant_key, display_name, status) values (?, ?, 'ACTIVE')",
            Statement.RETURN_GENERATED_KEYS
        )) {
            statement.setString(1, "upgrade-" + suffix);
            statement.setString(2, "Upgrade test tenant");
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                return keys.getLong(1);
            }
        }
    }

    private Long insertReviewTask(Connection connection, long tenantId, String suffix) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            insert into review_task (
                tenant_id, pr_number, title, repository, organization, commit_sha, branch_name,
                status, risk_level, mq_retries, publish_attempts, llm_status, pr_url,
                source, trigger_source, human_review_required, human_review_status,
                created_at, duration_seconds
            ) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """ , Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, tenantId);
            statement.setInt(2, 9900 + suffix.hashCode() % 99);
            statement.setString(3, "Flyway upgrade path task");
            statement.setString(4, "upgrade-repository");
            statement.setString(5, "upgrade-organization-" + suffix);
            statement.setString(6, COMMIT_SHA);
            statement.setString(7, "upgrade");
            statement.setString(8, "COMPLETED");
            statement.setString(9, "LOW");
            statement.setInt(10, 0);
            statement.setInt(11, 0);
            statement.setString(12, "NOT_REQUIRED");
            statement.setString(13, "https://example.invalid/upgrade/" + suffix);
            statement.setString(14, "MANUAL_INPUT");
            statement.setString(15, "MANUAL_INPUT");
            statement.setBoolean(16, false);
            statement.setString(17, "NOT_REQUIRED");
            statement.setObject(18, LocalDateTime.now());
            statement.setInt(19, 0);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                return keys.getLong(1);
            }
        }
    }

    private Long insertExecutionAttempt(Connection connection, long taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            insert into review_execution_attempt (
                task_id, attempt_no, generation, commit_sha, input_fingerprint, status,
                queued_at, started_at, created_at
            ) values (?, 1, 1, ?, ?, 'COMPLETED', ?, ?, ?)
            """, Statement.RETURN_GENERATED_KEYS)) {
            LocalDateTime now = LocalDateTime.now();
            statement.setLong(1, taskId);
            statement.setString(2, COMMIT_SHA);
            statement.setString(3, INPUT_FINGERPRINT);
            statement.setObject(4, now);
            statement.setObject(5, now);
            statement.setObject(6, now);
            statement.executeUpdate();
            try (ResultSet keys = statement.getGeneratedKeys()) {
                assertThat(keys.next()).isTrue();
                return keys.getLong(1);
            }
        }
    }

    private void updateCurrentAttempt(Connection connection, long taskId, long attemptId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "update review_task set current_attempt_id = ? where id = ?"
        )) {
            statement.setLong(1, attemptId);
            statement.setLong(2, taskId);
            statement.executeUpdate();
        }
    }

    private void insertChangedFile(
        Connection connection,
        long tenantId,
        long taskId,
        long attemptId,
        String suffix
    )
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            insert into changed_file (
                tenant_id, task_id, attempt_id, current_attempt, file_path, change_type, additions, deletions
            ) values (?, ?, ?, 1, ?, 'MODIFIED', 1, 0)
            """)) {
            statement.setLong(1, tenantId);
            statement.setLong(2, taskId);
            statement.setLong(3, attemptId);
            statement.setString(4, "src/upgrade/" + suffix + ".java");
            statement.executeUpdate();
        }
    }

    private long rowCount(Connection connection, String table, long tenantId, long taskId)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
            "select count(*) from " + table + " where tenant_id = ? and task_id = ?"
        )) {
            statement.setLong(1, tenantId);
            statement.setLong(2, taskId);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1);
            }
        }
    }

    private boolean constraintExists(Connection connection, String table, String constraint)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            select count(*)
            from information_schema.referential_constraints
            where constraint_schema = database() and table_name = ? and constraint_name = ?
            """)) {
            statement.setString(1, table);
            statement.setString(2, constraint);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1) == 1L;
            }
        }
    }

    private String latestSuccessfulMigration(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                 select version
                 from flyway_schema_history
                 where success = 1
                 order by installed_rank desc
                 limit 1
                 """)) {
            assertThat(result.next()).isTrue();
            return result.getString(1);
        }
    }

    private void deleteTenant(Connection connection, long tenantId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("delete from tenant where id = ?")) {
            statement.setLong(1, tenantId);
            statement.executeUpdate();
        }
    }

    private void cleanup(
        String url,
        String username,
        String password,
        Long tenantId,
        Long taskId,
        Long attemptId
    ) throws SQLException {
        if (tenantId == null || taskId == null) {
            return;
        }
        try (Connection connection = open(url, username, password);
             PreparedStatement changedFiles = connection.prepareStatement(
                 "delete from changed_file where tenant_id = ? and task_id = ?")) {
            changedFiles.setLong(1, tenantId);
            changedFiles.setLong(2, taskId);
            changedFiles.executeUpdate();
            if (attemptId != null) {
                try (PreparedStatement attempts = connection.prepareStatement(
                    "delete from review_execution_attempt where tenant_id = ? and id = ?"
                )) {
                    attempts.setLong(1, tenantId);
                    attempts.setLong(2, attemptId);
                    attempts.executeUpdate();
                }
            }
            try (PreparedStatement tasks = connection.prepareStatement("delete from review_task where tenant_id = ? and id = ?")) {
                tasks.setLong(1, tenantId);
                tasks.setLong(2, taskId);
                tasks.executeUpdate();
            }
            deleteTenant(connection, tenantId);
        }
    }

    private String requiredEnvironment(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " must be set for the migration integration test");
        }
        return value;
    }

    private String environmentOrDefault(String name, String fallback) {
        String value = System.getenv(name);
        return value == null ? fallback : value;
    }
}
