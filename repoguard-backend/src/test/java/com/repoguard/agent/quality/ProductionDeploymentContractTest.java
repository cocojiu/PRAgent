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
        int timeoutPreflight = script.lastIndexOf("\nvalidate_review_timeout_layering\n");
        int rabbitRestartDecision = script.lastIndexOf("\nif rabbitmq_config_requires_restart; then\n");
        int stopWorker = script.lastIndexOf("\nstop_inactive_split_worker\n");
        int infrastructureMutation = script.lastIndexOf("\ncompose up -d --no-deps mysql\n");
        int rollbackArmed = script.lastIndexOf("\nrollback_needed=true\n");

        assertThat(bindPreflight).isNotNegative().isLessThan(stopWorker);
        assertThat(timeoutPreflight).isNotNegative().isLessThan(stopWorker);
        assertThat(rabbitRestartDecision).isNotNegative().isLessThan(stopWorker);
        assertThat(rollbackArmed).isNotNegative().isLessThan(stopWorker);
        assertThat(stopWorker).isLessThan(infrastructureMutation);
        assertThat(script)
            .contains("compose up -d --no-deps --force-recreate rabbitmq")
            .contains("$DEPLOY_STATE_DIR/rabbitmq.conf.sha256")
            .contains("record_rabbitmq_config_digest");

        int rollbackStart = script.indexOf("rollback_deployment() {");
        int rollbackEnd = script.indexOf("\nrollback_needed=false", rollbackStart);
        String rollback = script.substring(rollbackStart, rollbackEnd);
        assertThat(rollback.indexOf("restore_deployment_assets"))
            .isNotNegative()
            .isLessThan(rollback.indexOf("compose up -d --no-deps --force-recreate rabbitmq"));
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
