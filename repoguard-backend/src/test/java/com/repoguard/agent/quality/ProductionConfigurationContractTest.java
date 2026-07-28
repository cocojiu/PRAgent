package com.repoguard.agent.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Locks the production configuration chain from the environment template
 * through Compose, application configuration, CI validation, and deployment.
 */
class ProductionConfigurationContractTest {

    private static final List<String> SHARED_CAPACITY_AND_SECURITY_KEYS = List.of(
        "REPOGUARD_SECURITY_ALLOW_PLAINTEXT_SECRETS",
        "REPOGUARD_TRUSTED_PROXY_NETWORKS",
        "REPOGUARD_ADMIN_API_KEY_MIN_LENGTH",
        "REPOGUARD_ADMIN_API_KEY_FAILED_REQUESTS_PER_MINUTE_PER_IP",
        "REPOGUARD_ADMIN_API_KEY_MAX_TRACKED_CLIENTS",
        "REPOGUARD_GITHUB_DIFF_MAX_PAGES",
        "REPOGUARD_GITHUB_DIFF_MAX_FILES",
        "REPOGUARD_GITHUB_DIFF_MAX_TOTAL_BYTES",
        "REPOGUARD_GITHUB_DIFF_MAX_PATCH_BYTES",
        "REPOGUARD_GITHUB_DIFF_TOTAL_TIMEOUT_MS",
        "REPOGUARD_REVIEW_PIPELINE_BUDGET_MS",
        "REPOGUARD_REVIEW_PIPELINE_MAX_TOTAL_CHUNKS",
        "REPOGUARD_REVIEW_PIPELINE_MAX_IN_FLIGHT_CHUNKS",
        "REPOGUARD_ASYNC_LLM_CHUNK_THREADS",
        "REPOGUARD_ASYNC_LLM_CHUNK_QUEUE_CAPACITY",
        "REPOGUARD_ASYNC_NOTIFICATION_PUBLISH_THREADS",
        "REPOGUARD_ASYNC_NOTIFICATION_PUBLISH_QUEUE_CAPACITY",
        "REPOGUARD_ASYNC_RECOVERY_THREADS",
        "REPOGUARD_ASYNC_RECOVERY_QUEUE_CAPACITY",
        "REPOGUARD_SCHEDULER_POOL_SIZE",
        "REPOGUARD_SCHEDULER_SHUTDOWN_WAIT",
        "REPOGUARD_LLM_BULKHEAD_MAX_CONCURRENT_CALLS",
        "REPOGUARD_LLM_BULKHEAD_MAX_WAIT_MILLIS"
    );

    @Test
    void logsStackStartsWithoutMetricsAndMetricsOverlayOwnsRemoteWrite() throws IOException {
        Path root = findRepositoryRoot();
        Map<String, Object> observability = yaml(root.resolve("docker-compose.observability.yml"));
        Map<String, Object> alloy = service(observability, "alloy");
        Map<String, Object> metrics = yaml(
            root.resolve("config/observability/docker-compose.metrics.yml")
        );
        Map<String, Object> metricsAlloy = service(metrics, "alloy");

        assertThat(alloy).doesNotContainKey("environment");
        assertThat(stringList(alloy.get("command"))).endsWith("/etc/alloy");
        assertThat(stringList(alloy.get("volumes")))
            .contains("./config/observability/alloy/config.alloy:/etc/alloy/config.alloy:ro")
            .noneMatch(volume -> volume.contains("metrics.alloy"));
        assertThat(alloy.get("mem_limit")).isEqualTo("${ALLOY_MEM_LIMIT:-256m}");

        Map<String, Object> metricsEnvironment = map(metricsAlloy.get("environment"));
        assertThat(metricsEnvironment)
            .containsEntry(
                "METRICS_REMOTE_WRITE_URL",
                "${METRICS_REMOTE_WRITE_URL:?Set METRICS_REMOTE_WRITE_URL when enabling metrics}"
            )
            .containsEntry(
                "METRICS_REMOTE_WRITE_USERNAME",
                "${METRICS_REMOTE_WRITE_USERNAME:?Set METRICS_REMOTE_WRITE_USERNAME when enabling metrics}"
            )
            .containsEntry(
                "METRICS_REMOTE_WRITE_PASSWORD",
                "${METRICS_REMOTE_WRITE_PASSWORD:?Set METRICS_REMOTE_WRITE_PASSWORD when enabling metrics}"
            );
        assertThat(stringList(metricsAlloy.get("volumes")))
            .containsExactly("./config/observability/alloy/metrics.alloy:/etc/alloy/metrics.alloy:ro");
        assertThat(metricsAlloy.get("mem_limit")).isEqualTo("${ALLOY_MEM_LIMIT:-320m}");

        String logConfig = read(root.resolve("config/observability/alloy/config.alloy"));
        String metricsConfig = read(root.resolve("config/observability/alloy/metrics.alloy"));
        assertThat(logConfig)
            .contains("loki.write \"local\"")
            .doesNotContain("prometheus.scrape")
            .doesNotContain("prometheus.remote_write");
        assertThat(metricsConfig)
            .contains("prometheus.scrape \"repoguard_backend\"")
            .contains("metrics_path    = \"/actuator/prometheus\"")
            .contains("prometheus.remote_write \"metrics_store\"")
            .contains("sys.env(\"METRICS_REMOTE_WRITE_URL\")");

        assertThat(networkRuleAction(blockBody(logConfig, "discovery.relabel \"repoguard\"")))
            .isEqualTo("drop");
        assertThat(networkRuleAction(blockBody(metricsConfig, "discovery.relabel \"repoguard_metrics\"")))
            .isEqualTo("keep");
    }

    @Test
    void metricsBridgeKeepsOptionalNetworkOutOfBaseProductionCompose() throws IOException {
        Path root = findRepositoryRoot();
        Map<String, Object> bridge = yaml(root.resolve("docker-compose.metrics-bridge.yml"));
        Map<String, Object> services = map(bridge.get("services"));
        Map<String, Object> observabilityNetwork = map(map(bridge.get("networks")).get("observability"));

        assertThat(services.keySet()).containsExactlyInAnyOrder("backend", "backend-worker");
        assertThat(stringList(map(services.get("backend")).get("networks")))
            .containsExactly("default", "observability");
        assertThat(stringList(map(services.get("backend-worker")).get("networks")))
            .containsExactly("default", "observability");
        assertThat(observabilityNetwork)
            .containsEntry("name", "repoguard_observability")
            .containsEntry("external", true);
        assertThat(yaml(root.resolve("docker-compose.prod.yml"))).doesNotContainKey("networks");
    }

    @Test
    void everyActiveProductionTemplateKeyHasARuntimeConsumer() throws IOException {
        Path root = findRepositoryRoot();
        Set<String> activeKeys = new LinkedHashSet<>();
        Pattern keyPattern = Pattern.compile("^([A-Z][A-Z0-9_]*)=");
        for (String line : read(root.resolve(".env.prod.example")).lines().toList()) {
            Matcher matcher = keyPattern.matcher(line);
            if (matcher.find()) {
                activeKeys.add(matcher.group(1));
            }
        }

        String consumers = String.join(
            "\n",
            read(root.resolve("docker-compose.prod.yml")),
            read(root.resolve("config/observability/docker-compose.metrics.yml")),
            read(root.resolve("scripts/deploy-prod.sh"))
        );
        assertThat(activeKeys).isNotEmpty();
        assertThat(activeKeys)
            .allSatisfy(key -> assertThat(consumers)
                .as("runtime consumer for .env key %s", key)
                .contains(key));
    }

    @Test
    void backendAndWorkerShareProductionCapacityAndSecurityMappings() throws IOException {
        Path root = findRepositoryRoot();
        Map<String, Object> production = yaml(root.resolve("docker-compose.prod.yml"));
        Map<String, Object> backendEnvironment = environment(production, "backend");
        Map<String, Object> workerEnvironment = environment(production, "backend-worker");

        assertThat(backendEnvironment).containsKeys(SHARED_CAPACITY_AND_SECURITY_KEYS.toArray(String[]::new));
        for (String key : SHARED_CAPACITY_AND_SECURITY_KEYS) {
            assertThat(workerEnvironment.get(key))
                .as("Worker mapping for %s", key)
                .isEqualTo(backendEnvironment.get(key));
        }

        String application = read(
            root.resolve("repoguard-backend/src/main/resources/application.yml")
        );
        String runtimeContract = read(
            root.resolve(
                "repoguard-backend/src/main/java/com/repoguard/agent/config/RuntimeRoleContract.java"
            )
        );
        Set<String> mappedKeys = new LinkedHashSet<>();
        mappedKeys.addAll(repoguardKeys(backendEnvironment));
        mappedKeys.addAll(repoguardKeys(workerEnvironment));
        assertThat(mappedKeys)
            .allSatisfy(key -> assertThat(application + runtimeContract)
                .as("application consumer for Compose key %s", key)
                .contains(key));
    }

    @Test
    void jdbcBatchingAndCoreSecretFailFastArePartOfProductionModels() throws IOException {
        Path root = findRepositoryRoot();
        Map<String, Object> production = yaml(root.resolve("docker-compose.prod.yml"));
        Map<String, Object> backendEnvironment = environment(production, "backend");
        Map<String, Object> workerEnvironment = environment(production, "backend-worker");

        for (Map<String, Object> environment : List.of(backendEnvironment, workerEnvironment)) {
            assertThat(environment.get("SPRING_DATASOURCE_URL").toString())
                .startsWith("${SPRING_DATASOURCE_URL:-jdbc:mysql://")
                .contains("rewriteBatchedStatements=true");
            assertThat(environment.get("SPRING_DATASOURCE_PASSWORD").toString()).contains(":?");
            assertThat(environment.get("SPRING_RABBITMQ_PASSWORD").toString()).contains(":?");
            for (String key : List.of(
                "REPOGUARD_SECURITY_ENCRYPTION_KEY",
                "REPOGUARD_SECURITY_ENCRYPTION_KEY_ID",
                "REPOGUARD_SECURITY_ENCRYPTION_SALT",
                "REPOGUARD_AUTH_TOKEN_SECRET",
                "REPOGUARD_ADMIN_API_KEY",
                "REPOGUARD_GITHUB_WEBHOOK_SECRET"
            )) {
                assertThat(environment.get(key).toString())
                    .as("fail-fast mapping for %s", key)
                    .contains(":?");
            }
        }

        assertThat(environment(production, "mysql").get("MYSQL_ROOT_PASSWORD").toString())
            .contains(":?");
        assertThat(environment(production, "mysql").get("MYSQL_PASSWORD").toString())
            .contains(":?");
        assertThat(environment(production, "rabbitmq").get("RABBITMQ_DEFAULT_PASS").toString())
            .contains(":?");

        Map<String, Object> smoke = yaml(root.resolve("docker-compose.smoke.yml"));
        assertThat(environment(smoke, "backend").get("SPRING_DATASOURCE_URL").toString())
            .contains("rewriteBatchedStatements=true");
        Map<String, Object> ip = yaml(root.resolve("docker-compose.ip.yml"));
        assertThat(environment(ip, "backend").get("SPRING_DATASOURCE_URL").toString())
            .contains("rewriteBatchedStatements=true");
        assertThat(read(root.resolve("repoguard-backend/src/main/resources/application-dev.yml")))
            .contains("rewriteBatchedStatements=true");
        assertThat(read(root.resolve("repoguard-backend/src/main/resources/application-test.yml")))
            .contains("rewriteBatchedStatements=true");
        assertThat(read(root.resolve(".github/workflows/pr-quality.yml")))
            .contains("rewriteBatchedStatements=true");
    }

    @Test
    void webhookBranchPolicyHasNoSharedTestDefaultAndFailsFastInProduction() throws IOException {
        Path root = findRepositoryRoot();
        String application = read(root.resolve("repoguard-backend/src/main/resources/application.yml"));
        String testApplication = read(
            root.resolve("repoguard-backend/src/main/resources/application-test.yml")
        );
        String properties = read(
            root.resolve(
                "repoguard-backend/src/main/java/com/repoguard/agent/github/webhook/GithubWebhookProperties.java"
            )
        );
        Map<String, Object> production = yaml(root.resolve("docker-compose.prod.yml"));
        Map<String, Object> ip = yaml(root.resolve("docker-compose.ip.yml"));
        Map<String, Object> smoke = yaml(root.resolve("docker-compose.smoke.yml"));
        String template = read(root.resolve(".env.prod.example"));
        String key = "REPOGUARD_GITHUB_WEBHOOK_ALLOWED_HEAD_BRANCHES";

        assertThat(application)
            .contains("allowed-head-branches: ${" + key + ":}")
            .doesNotContain(key + ":PRAgent-test");
        assertThat(properties)
            .contains("private List<String> allowedHeadBranches = new ArrayList<>();")
            .doesNotContain("List.of(\"PRAgent-test\")");
        assertThat(testApplication).contains("allowed-head-branches:", "- PRAgent-test");
        for (String service : List.of("backend", "backend-worker")) {
            assertThat(environment(production, service).get(key).toString()).contains(":?");
        }
        assertThat(environment(ip, "backend").get(key).toString()).contains(":?");
        assertThat(environment(smoke, "backend")).containsEntry(key, "PRAgent-test");
        assertThat(template).contains(key + "=main");
    }

    @Test
    void deploymentAndCiKeepTheOptionalMetricsTopology() throws IOException {
        Path root = findRepositoryRoot();
        String deploy = read(root.resolve("scripts/deploy-prod.sh"));
        String release = read(root.resolve(".github/workflows/release-images.yml"));
        String prQuality = read(root.resolve(".github/workflows/pr-quality.yml"));
        String observabilitySecurity = read(
            root.resolve(".github/workflows/production-observability-security.yml")
        );

        assertThat(deploy)
            .contains("COMPOSE_ADDITIONAL_FILES")
            .contains("COMPOSE_PATH_SEPARATOR=:")
            .contains("docker network inspect repoguard_observability")
            .contains("docker-compose.metrics-bridge.yml");
        assertThat(release)
            .contains("scp -i ~/.ssh/deploy_key -P \"${DEPLOY_PORT:-22}\" docker-compose.metrics-bridge.yml")
            .contains("'${asset_backup_dir}/docker-compose.metrics-bridge.yml'");
        for (String workflow : List.of(release, prQuality)) {
            assertThat(workflow)
                .contains("docker-compose.smoke.yml")
                .contains("docker-compose.metrics-bridge.yml")
                .contains("docker-compose.observability.yml")
                .contains("config/observability/docker-compose.metrics.yml");
        }
        assertThat(observabilitySecurity)
            .contains("validate /etc/alloy/config.alloy")
            .contains("validate /etc/alloy")
            .contains("alloy/metrics.alloy");
    }

    @Test
    void ciGeneratesRuntimeSecretsInsteadOfEmbeddingReusableCredentials() throws IOException {
        Path root = findRepositoryRoot();
        String prQuality = read(root.resolve(".github/workflows/pr-quality.yml"));
        String release = read(root.resolve(".github/workflows/release-images.yml"));

        for (String workflow : List.of(prQuality, release)) {
            assertThat(workflow)
                .contains("Generate ephemeral validation secrets")
                .contains("REPOGUARD_SECURITY_ENCRYPTION_KEY=$(openssl rand -hex 32)")
                .contains("SMOKE_AUTH_TOKEN_SECRET=$(openssl rand -hex 32)")
                .contains("SMOKE_ADMIN_API_KEY=$(openssl rand -hex 32)")
                .contains("REPOGUARD_GITHUB_WEBHOOK_SECRET=$(openssl rand -hex 32)")
                .doesNotContain("Validation-Encryption-Key-2026")
                .doesNotContain("Validation-Auth-Token-2026")
                .doesNotContain("Validation-Admin-Key-2026")
                .doesNotContain("Validation-Webhook-Secret-2026");
        }
        assertThat(prQuality)
            .contains("Generate ephemeral integration secrets")
            .contains("REPOGUARD_AUTH_TOKEN_SECRET=$(openssl rand -hex 32)")
            .doesNotContain("Integration-Key-2026!abc123XYZ-secure")
            .doesNotContain("Integration-Auth-Token-2026!abc123XYZ")
            .doesNotContain("Integration-Admin-Key-2026!abc123XYZ");
    }

    @Test
    void developmentInfrastructurePortsAreOnlyPublishedOnLoopback() throws IOException {
        Path root = findRepositoryRoot();
        Map<String, Object> development = yaml(root.resolve("repoguard-backend/docker-compose.yml"));

        assertThat(stringList(service(development, "mysql").get("ports")))
            .containsExactly("127.0.0.1:3306:3306");
        assertThat(stringList(service(development, "rabbitmq").get("ports")))
            .containsExactly(
                "127.0.0.1:5672:5672",
                "127.0.0.1:15672:15672"
            );
    }

    @Test
    void prometheusEndpointAndRegistryAreEnabledWithoutEmbeddingCredentials() throws IOException {
        Path root = findRepositoryRoot();
        String application = read(
            root.resolve("repoguard-backend/src/main/resources/application.yml")
        );
        String pom = read(root.resolve("repoguard-backend/pom.xml"));
        String template = read(root.resolve(".env.prod.example"));

        assertThat(application)
            .contains("include: health,info,metrics,prometheus")
            .contains("secure-cookies: ${REPOGUARD_AUTH_SECURE_COOKIES:true}");
        assertThat(pom).contains("<artifactId>micrometer-registry-prometheus</artifactId>");
        for (String line : template.lines().toList()) {
            if (line.startsWith("METRICS_REMOTE_WRITE_")) {
                assertThat(line)
                    .as("remote-write credentials must stay empty in the template")
                    .endsWith("=");
            }
        }
    }

    @Test
    void schedulerAndNetworkRecoveryWorkHaveIndependentBoundedCapacity() throws IOException {
        Path root = findRepositoryRoot();
        String application = read(root.resolve("repoguard-backend/src/main/resources/application.yml"));
        String notificationCompensator = read(root.resolve(
            "repoguard-backend/src/main/java/com/repoguard/agent/notification/"
                + "NotificationEventPublishCompensator.java"
        ));
        String reviewPublishCompensator = read(root.resolve(
            "repoguard-backend/src/main/java/com/repoguard/agent/messaging/"
                + "ReviewTaskPublishCompensator.java"
        ));
        String reviewRecoveryCompensator = read(root.resolve(
            "repoguard-backend/src/main/java/com/repoguard/agent/worker/"
                + "ReviewTaskRecoveryCompensator.java"
        ));
        String notificationCoordinator = read(root.resolve(
            "repoguard-backend/src/main/java/com/repoguard/agent/notification/"
                + "NotificationEventPublishCoordinator.java"
        ));

        assertThat(application)
            .contains("size: ${REPOGUARD_SCHEDULER_POOL_SIZE:4}")
            .contains("notification-publish-threads: ${REPOGUARD_ASYNC_NOTIFICATION_PUBLISH_THREADS:2}")
            .contains("recovery-threads: ${REPOGUARD_ASYNC_RECOVERY_THREADS:3}");
        for (String source : List.of(
            notificationCompensator,
            reviewPublishCompensator,
            reviewRecoveryCompensator
        )) {
            assertThat(source)
                .contains("RecoveryWorkDispatcher")
                .contains("recoveryWorkDispatcher.submit(");
        }
        assertThat(reviewPublishCompensator).contains("reviewTaskPublisher.publishOnce(");
        assertThat(reviewRecoveryCompensator).contains("reviewTaskPublisher.publishOnce(");
        assertThat(notificationCoordinator).contains("eventPublisher.publishOnce(");
    }

    private Set<String> repoguardKeys(Map<String, Object> environment) {
        Set<String> keys = new LinkedHashSet<>();
        environment.keySet().stream()
            .filter(key -> key.startsWith("REPOGUARD_"))
            .forEach(keys::add);
        return keys;
    }

    private String networkRuleAction(String relabelBody) {
        Matcher matcher = Pattern.compile(
            "source_labels\\s*=\\s*\\[\"__meta_docker_network_name\"]"
                + ".*?regex\\s*=\\s*\"repoguard_observability\""
                + ".*?action\\s*=\\s*\"(\\w+)\"",
            Pattern.DOTALL
        ).matcher(relabelBody);
        assertThat(matcher.find()).as("observability network relabel rule").isTrue();
        return matcher.group(1);
    }

    private String blockBody(String source, String header) {
        int headerIndex = source.indexOf(header);
        assertThat(headerIndex).as("Alloy block " + header).isNotNegative();
        int openingBrace = source.indexOf('{', headerIndex);
        int depth = 0;
        for (int index = openingBrace; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '{') {
                depth++;
            } else if (current == '}' && --depth == 0) {
                return source.substring(openingBrace + 1, index);
            }
        }
        fail("Unclosed Alloy block " + header);
        throw new IllegalStateException("unreachable");
    }

    private Map<String, Object> service(Map<String, Object> compose, String serviceName) {
        return map(map(compose.get("services")).get(serviceName));
    }

    private Map<String, Object> environment(Map<String, Object> compose, String serviceName) {
        return map(service(compose, serviceName).get("environment"));
    }

    private Path findRepositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve(".git"))
                && Files.isDirectory(current.resolve("repoguard-frontend"))) {
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
    private List<String> stringList(Object value) {
        return (List<String>) value;
    }

    private String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }
}
