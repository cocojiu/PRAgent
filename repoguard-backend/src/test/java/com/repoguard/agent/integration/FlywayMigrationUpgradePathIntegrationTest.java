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
 * with an isolated database and verifies the V76 expand state through the V93 CI SARIF upload state.
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
                insertTenantSingletonConfigs(connection, tenantId);
                taskId = insertReviewTask(connection, tenantId, suffix);
                attemptId = insertExecutionAttempt(connection, tenantId, taskId);
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

            migrateTo(url, username, password, "78");
            try (Connection connection = open(url, username, password)) {
                assertThat(latestSuccessfulMigration(connection)).isEqualTo("78");
                assertThat(uniqueIndexExists(
                    connection,
                    "review_policy_config",
                    "uk_review_policy_config_tenant"
                )).isTrue();
                assertThat(uniqueIndexExists(
                    connection,
                    "system_settings_config",
                    "uk_system_settings_config_tenant"
                )).isTrue();
                long finalTenantId = tenantId;
                assertThatThrownBy(() -> insertTenantSingletonConfigs(connection, finalTenantId))
                    .isInstanceOf(SQLException.class);
            }

            migrateTo(url, username, password, "79");
            try (Connection connection = open(url, username, password)) {
                assertThat(latestSuccessfulMigration(connection)).isEqualTo("79");
                assertThat(compositeUniqueIndexExists(
                    connection,
                    "github_check_run",
                    "uk_github_check_run_tenant_task_sequence",
                    3
                )).isTrue();
                assertThat(constraintExists(
                    connection,
                    "github_check_run",
                    "fk_github_check_run_tenant_task"
                )).isTrue();
                insertGithubCheckRun(connection, tenantId, taskId, suffix);
                long otherTenantId = insertTenant(connection, suffix + "-check-other");
                long finalTaskIdForCheck = taskId;
                try {
                    assertThatThrownBy(() -> insertGithubCheckRun(connection, otherTenantId, finalTaskIdForCheck, suffix + "-cross"))
                        .isInstanceOf(SQLException.class);
                } finally {
                    deleteTenant(connection, otherTenantId);
                }
            }

            migrateTo(url, username, password, "93");
            try (Connection connection = open(url, username, password)) {
                assertThat(latestSuccessfulMigration(connection)).isEqualTo("93");
                assertThat(columnExists(connection, "tenant_quota_config", "monthly_llm_token_budget"))
                    .isTrue();
                assertThat(columnExists(connection, "tenant_quota_config", "monthly_llm_cost_budget"))
                    .isTrue();
                assertThat(compositeUniqueIndexExists(
                    connection,
                    "llm_model_release",
                    "uk_llm_model_release_tenant_key",
                    2
                )).isTrue();
                assertThat(constraintExists(
                    connection,
                    "llm_model_release",
                    "fk_llm_model_release_tenant"
                )).isTrue();
                assertThat(columnExists(connection, "review_rule_config", "detector_type")).isTrue();
                assertThat(columnExists(connection, "review_rule_config", "matcher_expression")).isTrue();
                assertThat(columnExists(connection, "review_rule_config", "exception_patterns")).isTrue();
                assertThat(columnExists(connection, "review_rule_policy_snapshot", "detector_type")).isTrue();
                assertThat(columnExists(connection, "sarif_import_batch", "attempt_id")).isTrue();
                assertThat(compositeUniqueIndexExists(connection, "sarif_import_batch", "uk_sarif_batch_identity", 6))
                    .isTrue();
                assertThat(constraintExists(connection, "review_finding", "fk_review_finding_source_batch"))
                    .isTrue();
                assertThat(columnExists(connection, "llm_evaluation_report", "manifest_fingerprint")).isTrue();
                assertThat(columnExists(connection, "llm_evaluation_report", "verifier_version")).isTrue();
                assertThat(columnExists(connection, "llm_evaluation_report", "aggregation_version")).isTrue();
                assertThat(columnExists(connection, "llm_model_release", "evaluation_report_id")).isTrue();
                assertThat(constraintExists(connection, "llm_model_release", "fk_llm_model_release_evaluation_report"))
                    .isTrue();
                assertThat(columnExists(connection, "review_task", "llm_release_key")).isTrue();
                assertThat(compositeUniqueIndexExists(
                    connection, "llm_model_release_audit", "uk_llm_model_release_audit_tenant_id", 2
                )).isTrue();
                assertThat(constraintExists(
                    connection, "llm_model_release_audit", "fk_llm_model_release_audit_release"
                )).isTrue();
                assertThat(compositeUniqueIndexExists(
                    connection,
                    "github_check_run_policy",
                    "uk_github_check_run_policy_repository",
                    3
                )).isTrue();
                assertThat(constraintExists(
                    connection,
                    "github_check_run_policy",
                    "fk_github_check_run_policy_tenant"
                )).isTrue();
                assertThat(compositeUniqueIndexExists(
                    connection,
                    "llm_model_release_metric_snapshot",
                    "uk_llm_release_metric_window",
                    4
                )).isTrue();
                assertThat(constraintExists(
                    connection,
                    "llm_model_release_metric_snapshot",
                    "fk_llm_release_metric_release"
                )).isTrue();
                assertThat(columnIsNullable(connection, "notification_event", "task_id")).isTrue();
                assertThat(columnIsNullable(connection, "notification_delivery_log", "task_id")).isTrue();
                assertThat(columnExists(connection, "llm_evaluation_report", "lifecycle_status")).isTrue();
                assertThat(columnExists(connection, "llm_evaluation_report", "retention_days")).isTrue();
                assertThat(columnExists(connection, "llm_evaluation_report", "expires_at")).isTrue();
                assertThat(compositeUniqueIndexExists(
                    connection,
                    "llm_evaluation_report_lifecycle_audit",
                    "uk_llm_evaluation_report_lifecycle_audit_operation",
                    2
                )).isTrue();
                assertThat(constraintExists(
                    connection,
                    "llm_evaluation_report_lifecycle_audit",
                    "fk_llm_evaluation_report_lifecycle_audit_report"
                )).isTrue();
                assertThat(compositeUniqueIndexExists(
                    connection,
                    "llm_model_release_drift_audit",
                    "uk_llm_model_release_drift_audit_operation",
                    2
                )).isTrue();
                assertThat(constraintExists(
                    connection,
                    "llm_model_release_drift_audit",
                    "fk_llm_model_release_drift_audit_tenant"
                )).isTrue();
                assertThat(columnExists(connection, "sarif_ci_upload", "scan_run_id")).isTrue();
                assertThat(columnExists(connection, "sarif_ci_upload", "completion_time")).isTrue();
                assertThat(compositeUniqueIndexExists(
                    connection,
                    "sarif_ci_upload",
                    "uk_sarif_ci_upload_identity",
                    7
                )).isTrue();
                assertThat(constraintExists(
                    connection,
                    "sarif_ci_upload",
                    "fk_sarif_ci_upload_batch"
                )).isTrue();
            }
        } finally {
            cleanup(url, username, password, tenantId, taskId, attemptId);
        }
    }

    private void insertTenantSingletonConfigs(Connection connection, long tenantId) throws SQLException {
        try (PreparedStatement reviewPolicy = connection.prepareStatement("""
            insert into review_policy_config (
                tenant_id, llm_enabled, llm_provider, model_name, base_url, api_key_value,
                timeout_seconds, temperature, max_tokens, fallback_to_rules, worker_concurrency,
                chunk_file_threshold, chunk_line_threshold, chunk_max_files, chunk_max_lines,
                input_token_price_per_million, output_token_price_per_million, created_at, updated_at
            )
            select ?, llm_enabled, llm_provider, model_name, base_url, null,
                   timeout_seconds, temperature, max_tokens, fallback_to_rules, worker_concurrency,
                   chunk_file_threshold, chunk_line_threshold, chunk_max_files, chunk_max_lines,
                   input_token_price_per_million, output_token_price_per_million, now(), now()
              from review_policy_config where tenant_id = 1 order by id limit 1
            """)) {
            reviewPolicy.setLong(1, tenantId);
            reviewPolicy.executeUpdate();
        }
        try (PreparedStatement systemSettings = connection.prepareStatement("""
            insert into system_settings_config (
                tenant_id, system_name, language, timezone, retention_days, max_diff_lines,
                auto_comment, auto_retry, github_comment, high_risk_pr, failed_task,
                notification_email, webhook_signature, secret_masking, public_repo_allowed,
                token_ttl_days, created_at, updated_at
            )
            select ?, system_name, language, timezone, retention_days, max_diff_lines,
                   auto_comment, auto_retry, github_comment, high_risk_pr, failed_task,
                   notification_email, webhook_signature, secret_masking, public_repo_allowed,
                   token_ttl_days, now(), now()
              from system_settings_config where tenant_id = 1 order by id limit 1
            """)) {
            systemSettings.setLong(1, tenantId);
            systemSettings.executeUpdate();
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

    private Long insertExecutionAttempt(Connection connection, long tenantId, long taskId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            insert into review_execution_attempt (
                tenant_id, task_id, attempt_no, generation, commit_sha, input_fingerprint, status,
                queued_at, started_at, created_at
            ) values (?, ?, 1, 1, ?, ?, 'COMPLETED', ?, ?, ?)
            """, Statement.RETURN_GENERATED_KEYS)) {
            LocalDateTime now = LocalDateTime.now();
            statement.setLong(1, tenantId);
            statement.setLong(2, taskId);
            statement.setString(3, COMMIT_SHA);
            statement.setString(4, INPUT_FINGERPRINT);
            statement.setObject(5, now);
            statement.setObject(6, now);
            statement.setObject(7, now);
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

    private void insertGithubCheckRun(Connection connection, long tenantId, long taskId, String suffix)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            insert into github_check_run (
                tenant_id, task_id, run_sequence, name, head_sha, external_id,
                desired_stage, desired_version, applied_version, dispatch_attempts,
                created_at, updated_at
            ) values (?, ?, 1, 'RepoGuard PR Review', ?, ?, 'QUEUED', 1, 0, 0, now(), now())
            """)) {
            statement.setLong(1, tenantId);
            statement.setLong(2, taskId);
            statement.setString(3, COMMIT_SHA);
            statement.setString(4, "repoguard-upgrade-" + suffix);
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

    private boolean uniqueIndexExists(Connection connection, String table, String index)
        throws SQLException {
        return indexColumnCount(connection, table, index) == 1;
    }

    private boolean columnExists(Connection connection, String table, String column) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            select count(*)
              from information_schema.columns
             where table_schema = database() and table_name = ? and column_name = ?
            """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getLong(1) == 1L;
            }
        }
    }

    private boolean columnIsNullable(Connection connection, String table, String column) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            select is_nullable
              from information_schema.columns
             where table_schema = database() and table_name = ? and column_name = ?
            """)) {
            statement.setString(1, table);
            statement.setString(2, column);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return "YES".equalsIgnoreCase(result.getString(1));
            }
        }
    }

    private boolean compositeUniqueIndexExists(Connection connection, String table, String index, int expectedColumns)
        throws SQLException {
        return indexColumnCount(connection, table, index) == expectedColumns;
    }

    private int indexColumnCount(Connection connection, String table, String index)
        throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("""
            select count(*) as column_count, min(non_unique) as non_unique
              from information_schema.statistics
             where table_schema = database() and table_name = ? and index_name = ?
            """)) {
            statement.setString(1, table);
            statement.setString(2, index);
            try (ResultSet result = statement.executeQuery()) {
                assertThat(result.next()).isTrue();
                return result.getInt("non_unique") == 0
                    ? result.getInt("column_count")
                    : 0;
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
            try (PreparedStatement checkRuns = connection.prepareStatement(
                "delete from github_check_run where tenant_id = ? and task_id = ?"
            )) {
                checkRuns.setLong(1, tenantId);
                checkRuns.setLong(2, taskId);
                checkRuns.executeUpdate();
            }
            try (PreparedStatement tasks = connection.prepareStatement("delete from review_task where tenant_id = ? and id = ?")) {
                tasks.setLong(1, tenantId);
                tasks.setLong(2, taskId);
                tasks.executeUpdate();
            }
            try (PreparedStatement policies = connection.prepareStatement(
                "delete from review_policy_config where tenant_id = ?"
            ); PreparedStatement settings = connection.prepareStatement(
                "delete from system_settings_config where tenant_id = ?"
            )) {
                policies.setLong(1, tenantId);
                policies.executeUpdate();
                settings.setLong(1, tenantId);
                settings.executeUpdate();
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
