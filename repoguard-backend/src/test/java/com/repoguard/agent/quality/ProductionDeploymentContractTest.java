package com.repoguard.agent.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Protects the production deployment invariants that cannot be covered by a
 * Spring unit test: tracked bind assets, migration-owner ordering, broker
 * timeout layering, pre-mutation validation, and asset-aware rollback.
 */
class ProductionDeploymentContractTest {

    @Test
    void requiredBindSourcesUseFailClosedLongSyntax() throws IOException {
        Map<String, Object> services = services();

        Map<String, Object> rabbitConfig = bindMount(
            map(services.get("rabbitmq")),
            "./config/rabbitmq/rabbitmq.conf"
        );
        assertThat(rabbitConfig)
            .containsEntry("type", "bind")
            .containsEntry("target", "/etc/rabbitmq/conf.d/20-repoguard.conf")
            .containsEntry("read_only", true);
        assertThat(map(rabbitConfig.get("bind"))).containsEntry("create_host_path", false);

        Map<String, Object> caddyConfig = bindMount(map(services.get("caddy")), "./Caddyfile");
        assertThat(caddyConfig)
            .containsEntry("type", "bind")
            .containsEntry("target", "/etc/caddy/Caddyfile")
            .containsEntry("read_only", true);
        assertThat(map(caddyConfig.get("bind"))).containsEntry("create_host_path", false);
    }

    @Test
    void apiOwnsFlywayAndWorkerWaitsForTheMigrationOwner() throws IOException {
        Map<String, Object> services = services();
        Map<String, Object> backend = map(services.get("backend"));
        Map<String, Object> worker = map(services.get("backend-worker"));
        Map<String, Object> backendEnvironment = map(backend.get("environment"));
        Map<String, Object> workerEnvironment = map(worker.get("environment"));

        assertThat(backendEnvironment).doesNotContainKey("SPRING_FLYWAY_ENABLED");
        assertThat(workerEnvironment).containsEntry("SPRING_FLYWAY_ENABLED", "false");
        assertThat(map(map(worker.get("depends_on")).get("backend")))
            .containsEntry("condition", "service_healthy");
    }

    @Test
    void brokerTimeoutStaysBetweenPipelineBudgetAndRecoveryThreshold() throws IOException {
        Map<String, Object> services = services();
        Map<String, Object> backendEnvironment =
            map(map(services.get("backend")).get("environment"));
        Map<String, Object> workerEnvironment =
            map(map(services.get("backend-worker")).get("environment"));

        int pipelineBudget = interpolationDefault(
            backendEnvironment.get("REPOGUARD_REVIEW_PIPELINE_BUDGET_MS"),
            "REPOGUARD_REVIEW_PIPELINE_BUDGET_MS"
        );
        int recoveryTimeout = interpolationDefault(
            backendEnvironment.get("REPOGUARD_REVIEW_EXECUTION_TIMEOUT_MS"),
            "REPOGUARD_REVIEW_EXECUTION_TIMEOUT_MS"
        );
        int consumerTimeout = rabbitConsumerTimeout();

        assertThat(workerEnvironment.get("REPOGUARD_REVIEW_PIPELINE_BUDGET_MS"))
            .isEqualTo(backendEnvironment.get("REPOGUARD_REVIEW_PIPELINE_BUDGET_MS"));
        assertThat(workerEnvironment.get("REPOGUARD_REVIEW_EXECUTION_TIMEOUT_MS"))
            .isEqualTo(backendEnvironment.get("REPOGUARD_REVIEW_EXECUTION_TIMEOUT_MS"));
        assertThat(pipelineBudget).isLessThan(consumerTimeout);
        assertThat(consumerTimeout).isLessThan(recoveryTimeout);
    }

    @Test
    void releaseUploadsAndBacksUpRabbitConfigBeforePublishingCompose() throws IOException {
        Path workflowPath = repositoryRoot().resolve(".github/workflows/release-images.yml");
        String workflow = read(workflowPath);

        assertThat(yaml(workflowPath)).containsKey("jobs");
        assertThat(workflow)
            .contains("name: Validate production Compose models")
            .contains("docker compose --env-file .env.prod.example")
            .contains("docker compose --profile worker-split")
            .contains("test -s config/rabbitmq/rabbitmq.conf")
            .contains("${DEPLOY_PATH}/config/rabbitmq")
            .contains("${asset_backup_dir}/config/rabbitmq/rabbitmq.conf")
            .contains("DEPLOY_ASSET_BACKUP_DIR=.deploy-backup/");

        int uploadSection = workflow.indexOf("# Upload bind sources before the compose file");
        assertThat(uploadSection).isNotNegative();
        String orderedUploads = workflow.substring(uploadSection);
        assertThat(orderedUploads.indexOf("config/rabbitmq/rabbitmq.conf"))
            .isNotNegative()
            .isLessThan(orderedUploads.indexOf("docker-compose.prod.yml"));
    }

    @Test
    void deployPreflightsBeforeMutationAndRollbackRestoresAssetsFirst() throws IOException {
        String script = read(repositoryRoot().resolve("scripts/deploy-prod.sh"));

        int bindPreflight = script.lastIndexOf("\nvalidate_required_bind_sources\n");
        int secretPreflight = script.lastIndexOf("\nvalidate_secret_files\n");
        int edgePreflight = script.lastIndexOf("\nvalidate_edge_observability_isolation\n");
        int timeoutPreflight = script.lastIndexOf("\nvalidate_review_timeout_layering\n");
        int rabbitRestartDecision = script.lastIndexOf("\nif rabbitmq_config_requires_restart; then\n");
        int stopWorker = script.lastIndexOf("\nstop_inactive_split_worker\n");
        int infrastructureMutation = script.lastIndexOf("\ncompose up -d --no-deps mysql\n");
        int rollbackArmed = script.lastIndexOf("\nrollback_needed=true\n");
        int preflightOnlyExit = script.indexOf("if [ \"$PREFLIGHT_ONLY\" = \"true\" ]; then");
        int imagePull = script.indexOf("\ncompose pull $deploy_services\n");

        assertThat(bindPreflight).isNotNegative().isLessThan(stopWorker);
        assertThat(secretPreflight).isNotNegative().isLessThan(stopWorker);
        assertThat(edgePreflight).isNotNegative().isLessThan(stopWorker);
        assertThat(timeoutPreflight).isNotNegative().isLessThan(stopWorker);
        assertThat(rabbitRestartDecision).isNotNegative().isLessThan(stopWorker);
        assertThat(rollbackArmed).isNotNegative().isLessThan(stopWorker);
        assertThat(stopWorker).isLessThan(infrastructureMutation);
        assertThat(preflightOnlyExit).isNotNegative().isLessThan(imagePull);
        assertThat(script)
            .contains("compose up -d --no-deps --force-recreate rabbitmq")
            .contains("$DEPLOY_STATE_DIR/rabbitmq.conf.sha256")
            .contains("record_rabbitmq_config_digest")
            .contains("REPOGUARD_SECURITY_ENCRYPTION_KEY_FILE")
            .contains("REPOGUARD_GITHUB_WEBHOOK_SECRET_FILE")
            .contains("400|600")
            .contains("500|700")
            .contains("tail -c 1")
            .contains("contains a trailing newline")
            .contains("Production edge configuration must not route to observability services")
            .contains("Production deployment preflight passed; no image was pulled and no service was changed.");

        int rollbackStart = script.indexOf("rollback_deployment() {");
        int rollbackEnd = script.indexOf("\nrollback_needed=false", rollbackStart);
        String rollback = script.substring(rollbackStart, rollbackEnd);
        assertThat(rollback.indexOf("restore_deployment_assets"))
            .isNotNegative()
            .isLessThan(rollback.indexOf("compose up -d --no-deps --force-recreate rabbitmq"));
    }

    @Test
    void legacySecretMigrationIsExplicitExactAndFinalizedOnlyAfterHealth() throws IOException {
        Path root = repositoryRoot();
        String workflow = read(root.resolve(".github/workflows/release-images.yml"));
        String deploy = read(root.resolve("scripts/deploy-prod.sh"));
        String migration = read(root.resolve("scripts/migrate-prod-secret-files.sh"));

        assertThat(workflow)
            .contains("migrate_legacy_secret_files:")
            .contains("default: false")
            .contains("name: Exercise legacy production secret migration")
            .contains("scripts/migrate-prod-secret-files.sh")
            .contains("MIGRATE_LEGACY_SECRET_FILES: ${{ inputs.migrate_legacy_secret_files }}")
            .contains("MIGRATE_LEGACY_SECRET_FILES='${MIGRATE_LEGACY_SECRET_FILES}'");

        int prepare = deploy.lastIndexOf("sh scripts/migrate-prod-secret-files.sh prepare");
        int preflight = deploy.lastIndexOf("\nvalidate_required_bind_sources\n");
        int pull = deploy.lastIndexOf("\ncompose pull $deploy_services\n");
        int healthVerification = deploy.lastIndexOf("\nverify_deployment 15 30\n");
        int rollbackDisarmed = deploy.lastIndexOf("\nrollback_needed=false\n");
        int finalize = deploy.lastIndexOf("sh scripts/migrate-prod-secret-files.sh finalize");
        assertThat(prepare).isNotNegative().isLessThan(preflight);
        assertThat(preflight).isLessThan(pull);
        assertThat(healthVerification).isLessThan(rollbackDisarmed);
        assertThat(rollbackDisarmed).isLessThan(finalize);

        assertThat(migration)
            .contains(
                "MYSQL_ROOT_PASSWORD|MYSQL_ROOT_PASSWORD_FILE|./secrets/mysql.root-password",
                "MYSQL_PASSWORD|MYSQL_PASSWORD_FILE|./secrets/spring.datasource.password",
                "REPOGUARD_SECURITY_ENCRYPTION_KEY|REPOGUARD_SECURITY_ENCRYPTION_KEY_FILE"
                    + "|./secrets/repoguard.security.encryption-key",
                "REPOGUARD_SECURITY_ENCRYPTION_SALT|REPOGUARD_SECURITY_ENCRYPTION_SALT_FILE"
                    + "|./secrets/repoguard.security.encryption-salt",
                "REPOGUARD_AUTH_TOKEN_SECRET|REPOGUARD_AUTH_TOKEN_SECRET_FILE"
                    + "|./secrets/repoguard.auth.token-secret",
                "REPOGUARD_ADMIN_API_KEY|REPOGUARD_ADMIN_API_KEY_FILE"
                    + "|./secrets/app.security.admin-api-key.key",
                "REPOGUARD_GITHUB_WEBHOOK_SECRET|REPOGUARD_GITHUB_WEBHOOK_SECRET_FILE"
                    + "|./secrets/app.github.webhook.secret"
            )
            .contains("printf '%s' \"$legacy_value\" > \"$candidate\"")
            .contains("cmp -s \"$candidate\" \"$secret_path\"")
            .contains("unset \"$legacy_key\"")
            .contains("config --environment")
            .contains("rewrite_env true \"$backup_directory\"")
            .contains("rewrite_env false \"$backup_directory\"")
            .contains("Prepared production secret files without removing legacy fallback keys.")
            .contains("Removed legacy inline secret keys after successful deployment verification.")
            .doesNotContain("openssl rand", "/dev/urandom", "date +%s%N");
    }

    @Test
    void mysqlBackupConsumesTheRootPasswordFileWithoutPuttingTheSecretInArguments() throws IOException {
        String script = read(repositoryRoot().resolve("scripts/backup-prod-mysql.sh"));

        assertThat(script)
            .contains("MYSQL_ROOT_PASSWORD_FILE")
            .contains("mysql_root_password=\"$(cat \"$MYSQL_ROOT_PASSWORD_FILE\")\"")
            .contains("MYSQL_PWD=\"$mysql_root_password\"")
            .doesNotContain("MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\"");
    }

    private int rabbitConsumerTimeout() throws IOException {
        String config = read(repositoryRoot().resolve("config/rabbitmq/rabbitmq.conf"));
        Matcher matcher = Pattern.compile(
            "(?m)^\\s*consumer_timeout\\s*=\\s*(\\d+)\\s*$"
        ).matcher(config);
        assertThat(matcher.find()).as("RabbitMQ consumer_timeout must be configured").isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private int interpolationDefault(Object value, String key) {
        Matcher matcher = Pattern.compile(
            "\\$\\{" + Pattern.quote(key) + ":-(\\d+)}"
        ).matcher(String.valueOf(value));
        assertThat(matcher.find()).as("%s must declare a numeric compose default", key).isTrue();
        return Integer.parseInt(matcher.group(1));
    }

    private Map<String, Object> services() throws IOException {
        return map(yaml(repositoryRoot().resolve("docker-compose.prod.yml")).get("services"));
    }

    private Map<String, Object> bindMount(Map<String, Object> service, String source) {
        for (Object volume : list(service.get("volumes"))) {
            if (volume instanceof Map<?, ?> candidate && source.equals(candidate.get("source"))) {
                return map(candidate);
            }
        }
        fail("Missing bind mount source " + source);
        throw new IllegalStateException("unreachable");
    }

    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve(".git"))
                && Files.isDirectory(current.resolve("repoguard-backend"))) {
                return current;
            }
            current = current.getParent();
        }
        fail("Cannot locate repository root from " + Path.of("").toAbsolutePath());
        throw new IllegalStateException("unreachable");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> yaml(Path path) throws IOException {
        return (Map<String, Object>) new Yaml().load(read(path));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    private List<Object> list(Object value) {
        return (List<Object>) value;
    }

    private String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
