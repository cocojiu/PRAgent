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
        Set<String> serviceSpecificKeys = Set.of(
            "JAVA_TOOL_OPTIONS",
            "SPRING_FLYWAY_ENABLED",
            "REPOGUARD_RUNTIME_ROLE",
            "REPOGUARD_API_INSTANCE_COUNT",
            "REPOGUARD_AUTH_REGISTRATION_ENABLED"
        );

        assertThat(backendEnvironment).containsKeys(SHARED_CAPACITY_AND_SECURITY_KEYS.toArray(String[]::new));
        for (String key : SHARED_CAPACITY_AND_SECURITY_KEYS) {
            assertThat(workerEnvironment.get(key))
                .as("Worker mapping for %s", key)
                .isEqualTo(backendEnvironment.get(key));
        }
        assertThat(workerEnvironment.keySet()).containsAll(backendEnvironment.keySet());
        for (String key : backendEnvironment.keySet()) {
            if (!serviceSpecificKeys.contains(key)) {
                assertThat(workerEnvironment.get(key))
                    .as("final merged Worker mapping for %s", key)
                    .isEqualTo(backendEnvironment.get(key));
            }
        }
        assertThat(service(production, "backend").get("secrets"))
            .isEqualTo(service(production, "backend-worker").get("secrets"));
        assertThat(read(root.resolve("docker-compose.prod.yml")))
            .contains("x-backend-environment: &backend-environment")
            .contains("x-backend-secrets: &backend-secrets")
            .contains("<<: *backend-environment")
            .contains("secrets: *backend-secrets");

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
    void jdbcBatchingAndFileBackedSecretsArePartOfProductionModels() throws IOException {
        Path root = findRepositoryRoot();
        Map<String, Object> production = yaml(root.resolve("docker-compose.prod.yml"));
        Map<String, Object> backendEnvironment = environment(production, "backend");
        Map<String, Object> workerEnvironment = environment(production, "backend-worker");
        List<String> fileBackedEnvironmentKeys = List.of(
            "SPRING_DATASOURCE_PASSWORD",
            "REPOGUARD_SECURITY_ENCRYPTION_KEY",
            "REPOGUARD_SECURITY_ENCRYPTION_SALT",
            "REPOGUARD_AUTH_TOKEN_SECRET",
            "REPOGUARD_ADMIN_API_KEY",
            "REPOGUARD_GITHUB_WEBHOOK_SECRET"
        );

        for (Map<String, Object> environment : List.of(backendEnvironment, workerEnvironment)) {
            assertThat(environment.get("SPRING_DATASOURCE_URL").toString())
                .startsWith("${SPRING_DATASOURCE_URL:-jdbc:mysql://")
                .contains("rewriteBatchedStatements=true");
            assertThat(environment.get("SPRING_RABBITMQ_PASSWORD").toString()).contains(":?");
            assertThat(environment).doesNotContainKeys(fileBackedEnvironmentKeys.toArray(String[]::new));
            assertThat(environment.get("REPOGUARD_SECURITY_ENCRYPTION_KEY_ID").toString()).contains(":?");
        }

        assertThat(environment(production, "mysql"))
            .containsEntry("MYSQL_ROOT_PASSWORD_FILE", "/run/secrets/mysql.root-password")
            .containsEntry("MYSQL_PASSWORD_FILE", "/run/secrets/spring.datasource.password")
            .doesNotContainKeys("MYSQL_ROOT_PASSWORD", "MYSQL_PASSWORD");
        assertThat(environment(production, "rabbitmq").get("RABBITMQ_DEFAULT_PASS").toString())
            .contains(":?");

        Map<String, Object> secrets = map(production.get("secrets"));
        assertThat(secrets.keySet()).containsExactlyInAnyOrder(
            "mysql_root_password",
            "mysql_password",
            "security_encryption_key",
            "security_encryption_salt",
            "auth_token_secret",
            "admin_api_key",
            "github_webhook_secret"
        );
        assertThat(secrets)
            .allSatisfy((name, value) -> assertThat(map(value).get("file").toString())
                .as("fail-fast file setting for %s", name)
                .contains(":?"));
        assertThat(service(production, "backend").get("secrets").toString())
            .contains(
                "spring.datasource.password",
                "repoguard.security.encryption-key",
                "repoguard.security.encryption-salt",
                "repoguard.auth.token-secret",
                "app.security.admin-api-key.key",
                "app.github.webhook.secret"
            );
        assertThat(read(root.resolve("repoguard-backend/src/main/resources/application-prod.yml")))
            .contains("import: optional:configtree:/run/secrets/");
        assertThat(read(root.resolve(".env.prod.example")))
            .contains("MYSQL_ROOT_PASSWORD_FILE=./secrets/mysql.root-password")
            .contains("REPOGUARD_AUTH_TOKEN_SECRET_FILE=./secrets/repoguard.auth.token-secret")
            .doesNotContain("\nMYSQL_ROOT_PASSWORD=", "\nMYSQL_PASSWORD=")
            .doesNotContain("\nREPOGUARD_ADMIN_API_KEY=", "\nREPOGUARD_GITHUB_WEBHOOK_SECRET=");

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
                .contains("Exercise production deployment preflight")
                .contains("PREFLIGHT_ONLY=true")
                .contains("encryption_key=\"CI!1-$(openssl rand -hex 32)\"")
                .contains("install -d -m 700 \"${secret_dir}\"")
                .contains("printf '%s' \"${value}\" > \"${path}\"")
                .contains("chmod 600 \"${path}\"")
                .contains("write_secret MYSQL_ROOT_PASSWORD_FILE mysql.root-password")
                .contains("write_secret REPOGUARD_AUTH_TOKEN_SECRET_FILE repoguard.auth.token-secret")
                .contains("write_secret REPOGUARD_GITHUB_WEBHOOK_SECRET_FILE app.github.webhook.secret")
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
    void gitleaksPinsPrPushAndFullHistoryScanningWithFingerprintOnlyExceptions() throws IOException {
        Path root = findRepositoryRoot();
        String action = "gitleaks/gitleaks-action@e0c47f4f8be36e29cdc102c57e68cb5cbf0e8d1e";
        String prQuality = read(root.resolve(".github/workflows/pr-quality.yml"));
        String release = read(root.resolve(".github/workflows/release-images.yml"));
        String history = read(root.resolve(".github/workflows/secret-history-scan.yml"));

        for (String workflow : List.of(prQuality, release, history)) {
            assertThat(workflow)
                .contains(action)
                .contains("fetch-depth: 0")
                .contains("GITLEAKS_VERSION: 8.30.1")
                .contains("GITLEAKS_ENABLE_UPLOAD_ARTIFACT: \"false\"");
        }
        assertThat(history).contains("schedule:", "workflow_dispatch:");

        List<String> ignoredFingerprints = read(root.resolve(".gitleaksignore"))
            .lines()
            .map(String::trim)
            .filter(line -> !line.isEmpty() && !line.startsWith("#"))
            .toList();
        assertThat(ignoredFingerprints).containsExactly(
            "adb052dff215ba677c2e3496269facd5dfbd3127:.github/workflows/pr-quality.yml:generic-api-key:39",
            "adb052dff215ba677c2e3496269facd5dfbd3127:.github/workflows/release-images.yml:generic-api-key:77",
            "e884d8c4a5c760a415837a7ddb2927f0f33ebe59:repoguard-backend/src/test/java/com/repoguard/agent/external/ExternalHttpFailureDetailTest.java:generic-api-key:31"
        );
    }

    @Test
    void serverBootstrapUsesSecureRandomnessAndCreatesFileBackedSecrets() throws IOException {
        String bootstrap = read(findRepositoryRoot().resolve("scripts/bootstrap-docker-server.sh"));

        assertThat(bootstrap)
            .contains("openssl rand -base64 48")
            .contains("/dev/urandom")
            .contains("printf '%s' \"$value\" > \"$target\"")
            .contains("chmod 600 \"$target\"")
            .contains("MYSQL_ROOT_PASSWORD_FILE=./secrets/mysql.root-password")
            .contains("REPOGUARD_SECURITY_ENCRYPTION_KEY_FILE=./secrets/repoguard.security.encryption-key")
            .contains("REPOGUARD_GITHUB_WEBHOOK_SECRET_FILE=./secrets/app.github.webhook.secret")
            .doesNotContain("date +%s%N");
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

    @Test
    void applicationContainersAreHealthCheckedAndLeastPrivilege() throws IOException {
        Path root = findRepositoryRoot();
        Map<String, Object> production = yaml(root.resolve("docker-compose.prod.yml"));
        Map<String, Object> ipDeployment = yaml(root.resolve("docker-compose.ip.yml"));

        for (String serviceName : List.of("backend", "backend-worker", "frontend", "caddy")) {
            assertLeastPrivilegeApplicationContainer(production, serviceName);
            assertThat(map(service(production, serviceName).get("healthcheck")))
                .as("production healthcheck for %s", serviceName)
                .containsKey("test");
        }
        for (String serviceName : List.of("backend", "frontend")) {
            assertLeastPrivilegeApplicationContainer(ipDeployment, serviceName);
            assertThat(map(service(ipDeployment, serviceName).get("healthcheck")))
                .as("IP deployment healthcheck for %s", serviceName)
                .containsKey("test");
        }

        Map<String, Object> frontendDependency = map(map(service(production, "caddy").get("depends_on"))
            .get("frontend"));
        assertThat(frontendDependency).containsEntry("condition", "service_healthy");
        assertThat(stringList(map(service(production, "frontend").get("healthcheck")).get("test")).toString())
            .contains("/healthz");
        assertThat(stringList(map(service(production, "caddy").get("healthcheck")).get("test")).toString())
            .contains("/healthz");
        assertThat(read(root.resolve("repoguard-frontend/nginx.ip.conf")))
            .contains("location = /healthz")
            .contains("return 200 \"ok\\n\"");
        assertThat(read(root.resolve("Caddyfile")))
            .contains("path /healthz")
            .contains("respond \"ok\" 200");
    }

    private void assertLeastPrivilegeApplicationContainer(
        Map<String, Object> compose,
        String serviceName
    ) {
        Map<String, Object> application = service(compose, serviceName);
        assertThat(application)
            .as("least-privilege configuration for %s", serviceName)
            .containsEntry("read_only", true);
        assertThat(stringList(application.get("cap_drop"))).containsExactly("ALL");
        assertThat(stringList(application.get("security_opt"))).contains("no-new-privileges:true");
        assertThat(stringList(application.get("tmpfs"))).isNotEmpty();

        Map<String, Object> logging = map(application.get("logging"));
        assertThat(logging).containsEntry("driver", "json-file");
        assertThat(map(logging.get("options"))).containsKeys("max-size", "max-file");
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
