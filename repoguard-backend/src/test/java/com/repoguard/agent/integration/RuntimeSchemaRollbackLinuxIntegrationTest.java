package com.repoguard.agent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.ConnectionFactory;
import com.repoguard.agent.review.task.ReviewFailureSummaryResolver.ReviewFailureDetails;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Exercises immutable release images on owned, egress-isolated infrastructure, never production. */
@EnabledOnOs(OS.LINUX)
@EnabledIfEnvironmentVariable(named = "REPOGUARD_RUN_LINUX_BACKUP_INTEGRATION", matches = "true")
class RuntimeSchemaRollbackLinuxIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String MYSQL_IMAGE = "mysql:8.0.46@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b";
    private static final String RABBIT_IMAGE = "rabbitmq:3.13.7@sha256:87178a0ee3e2f52980ba356d38646ed1056705ff2d5ff281f8965456eaa0c1e3";
    private static final String OWNER = "rollback-" + UUID.randomUUID().toString().replace("-", "");
    private static final String LABEL = "com.repoguard.ci.owner";
    private static final String DATABASE = "repoguard_rollback";
    private static final String VHOST = "/rollback";
    private static final String QUEUE = "repoguard.review.queue.v3";
    private static final List<String> CONTAINERS = new ArrayList<>();
    private static final List<String> SECRETS = new ArrayList<>();
    private static final Map<String, Object> PROOF = new LinkedHashMap<>();
    private static final Path EVIDENCE = Path.of("target/runtime-rollback");
    @TempDir static Path temporary;
    private static String network;
    private static String mysql;
    private static String rabbit;
    private static String previousImage;
    private static String upgradeImage;
    private static String previousRevision;
    private static String upgradeRevision;
    private static String rabbitPassword;
    private static String rabbitHost;
    private static Path applicationEnvironment;

    @BeforeAll static void prepare() throws Exception {
        assertThat(System.getenv("GITHUB_ACTIONS")).isEqualTo("true");
        assertThat(System.getenv("RUNNER_ENVIRONMENT")).isEqualTo("github-hosted");
        assertThat(System.getenv("GITHUB_RUN_ID")).matches("[1-9][0-9]*");
        previousImage = image("REPOGUARD_ROLLBACK_PREVIOUS_IMAGE");
        upgradeImage = image("REPOGUARD_ROLLBACK_UPGRADE_IMAGE");
        previousRevision = revision("REPOGUARD_ROLLBACK_PREVIOUS_REVISION");
        upgradeRevision = revision("REPOGUARD_ROLLBACK_UPGRADE_REVISION");
        assertThat(previousImage).isNotEqualTo(upgradeImage);
        Files.createDirectories(EVIDENCE);
        PROOF.put("startedAt", Instant.now().toString());
        PROOF.put("runId", System.getenv("GITHUB_RUN_ID"));
        PROOF.put("productionWrites", 0);
        PROOF.put("externalEgress", false);
        PROOF.put("naturalBusinessReplay", false);
        PROOF.put("previousImage", previousImage);
        PROOF.put("previousRevision", previousRevision);
        PROOF.put("upgradeImage", upgradeImage);
        PROOF.put("upgradeRevision", upgradeRevision);
        saveProof();
        for (String image : List.of(MYSQL_IMAGE, RABBIT_IMAGE, previousImage, upgradeImage)) {
            checked(List.of("docker", "pull", "--platform", "linux/amd64", image), Duration.ofMinutes(3));
        }
        network = checked(List.of("docker", "network", "create", "--internal", "--driver", "bridge",
            "--label", LABEL + "=" + OWNER, "--opt", "com.docker.network.bridge.host_binding_ipv4=127.0.0.1", OWNER));
        JsonNode networkState = inspect("network", network);
        assertThat(networkState.path("Internal").asBoolean()).isTrue();
        assertThat(networkState.path("Labels").path(LABEL).asText()).isEqualTo(OWNER);
        String rootPassword = secret(); String databasePassword = secret(); rabbitPassword = secret();
        mysql = container("mysql", MYSQL_IMAGE, environment("mysql", Map.of(
            "MYSQL_ROOT_PASSWORD", rootPassword, "MYSQL_DATABASE", DATABASE,
            "MYSQL_USER", "repoguard", "MYSQL_PASSWORD", databasePassword)), List.of("--network-alias", "mysql"), List.of());
        await(() -> succeeds(List.of("docker", "exec", mysql, "sh", "-ec",
            "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql --protocol=TCP -h127.0.0.1 -uroot -N -e 'SELECT 1'")), 120);
        rabbit = container("rabbit", RABBIT_IMAGE, environment("rabbit", Map.of(
            "RABBITMQ_DEFAULT_USER", "repoguard", "RABBITMQ_DEFAULT_PASS", rabbitPassword,
            "RABBITMQ_DEFAULT_VHOST", VHOST)), List.of("--network-alias", "rabbitmq"), List.of());
        // A root CLI probe can create a root-owned cookie before the entrypoint drops privileges.
        await(() -> succeeds(List.of("docker", "exec", "--user", "rabbitmq", rabbit, "rabbitmq-diagnostics", "-q", "ping")), 90);
        // Internal bridges do not publish host ports; the trusted Linux host can use the owned bridge IP.
        JsonNode broker = inspect("container", rabbit).path("NetworkSettings");
        assertThat(broker.path("Ports").path("5672/tcp").isNull()).isTrue();
        JsonNode attachment = broker.path("Networks").path(OWNER);
        assertThat(attachment.path("NetworkID").asText()).isEqualTo(network);
        rabbitHost = attachment.path("IPAddress").asText();
        assertThat(rabbitHost).matches("[0-9.]{7,15}");
        assertThat(InetAddress.getByName(rabbitHost).isSiteLocalAddress()).isTrue();
        var env = new LinkedHashMap<String, String>();
        env.put("SPRING_PROFILES_ACTIVE", "prod");
        env.put("SERVER_PORT", "8081");
        env.put("SPRING_DATASOURCE_URL", "jdbc:mysql://mysql:3306/" + DATABASE + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&connectTimeout=3000&socketTimeout=30000");
        env.put("SPRING_DATASOURCE_USERNAME", "repoguard"); env.put("SPRING_DATASOURCE_PASSWORD", databasePassword);
        env.put("SPRING_RABBITMQ_HOST", "rabbitmq"); env.put("SPRING_RABBITMQ_PORT", "5672");
        env.put("SPRING_RABBITMQ_USERNAME", "repoguard"); env.put("SPRING_RABBITMQ_PASSWORD", rabbitPassword);
        env.put("SPRING_RABBITMQ_VIRTUAL_HOST", VHOST);
        env.put("REPOGUARD_SECURITY_ENCRYPTION_KEY", "CI!1-" + secret());
        env.put("REPOGUARD_SECURITY_ENCRYPTION_KEY_ID", "rollback-ci");
        env.put("REPOGUARD_SECURITY_ENCRYPTION_SALT", secret());
        env.put("REPOGUARD_AUTH_TOKEN_SECRET", secret()); env.put("REPOGUARD_ADMIN_API_KEY", secret());
        env.put("APP_CORS_ALLOWED_ORIGINS", "https://rollback.invalid");
        env.put("REPOGUARD_DEPLOYMENT_MODE", "split");
        env.put("REPOGUARD_AUTH_REGISTRATION_ENABLED", "false");
        env.put("REPOGUARD_GITHUB_FEEDBACK_ENABLED", "false");
        env.put("REPOGUARD_GITHUB_CHECK_RUN_ENABLED", "false");
        env.put("REPOGUARD_GITHUB_WEBHOOK_ENABLED", "false");
        env.put("JAVA_TOOL_OPTIONS", "-Xms64m -Xmx320m");
        applicationEnvironment = environment("application", env);
    }

    @Test @Timeout(value = 15, unit = TimeUnit.MINUTES)
    void upgradesTo107AndRollsBothRolesBackTo106WithoutLosingDataOrMessages() throws Exception {
        String baseline = application("baseline-api", previousImage, previousRevision, "api");
        assertThat(schema()).isEqualTo("106");
        PROOF.put("baselineSchema", 106); saveProof();
        sql("UPDATE review_policy_config SET llm_enabled=0; " +
            "INSERT INTO review_task (id,tenant_id,organization,repository,pr_number,title,commit_sha,branch_name,status,risk_level,llm_status,assessment_status,created_at,finished_at) " +
            "VALUES (7001,1,'rollback-fixture','retained',1,'Terminal duplicate fixture','" + "a".repeat(40) + "','main','COMPLETED','LOW','SKIPPED','COMPLETE',NOW(),NOW());");
        String retainedBefore = sql("SELECT CONCAT(id,':',status,':',commit_sha) FROM review_task WHERE id=7001");
        stop(baseline);

        String upgradedApi = application("upgrade-api", upgradeImage, upgradeRevision, "api");
        assertThat(schema()).isEqualTo("107");
        sql("INSERT INTO review_task (id,tenant_id,organization,repository,pr_number,title,commit_sha,branch_name,status,risk_level,llm_status,assessment_status,created_at,finished_at,failure_details_json) " +
            "VALUES (7002,1,'rollback-fixture','retained',2,'Structured failure fixture','" + "b".repeat(40) + "','main','FAILED','UNKNOWN','FAILED','FAILED',NOW(),NOW(),JSON_OBJECT('category','review_timeout','retryable',CAST('true' AS JSON),'stage','REVIEW_EXECUTION','attemptId',NULL,'retryAfter',''));");
        String structuredBefore = sql("SELECT failure_details_json FROM review_task WHERE id=7002");
        assertThat(ReviewFailureDetails.parse(structuredBefore, JSON)).isPresent();
        String upgradedWorker = application("upgrade-worker", upgradeImage, upgradeRevision, "worker");
        assertConcurrentRoles(upgradedApi, upgradedWorker);
        PROOF.put("upgrade", Map.of("schema", schema(), "apiAndWorkerReady", true, "reviewConsumers", 1)); saveProof();
        stop(upgradedWorker); stop(upgradedApi);
        await(() -> { try { return queue().path("consumers").asInt(-1) == 0; } catch (Exception e) { return false; } }, 30);

        String rollbackApi = application("rollback-api", previousImage, previousRevision, "api");
        assertThat(schema()).isEqualTo("107");
        assertThat(queue().path("consumers").asInt()).isZero();
        publishTerminalDuplicate();
        assertThat(queue().path("messages_ready").asInt()).isEqualTo(1);
        String rollbackWorker = application("rollback-worker", previousImage, previousRevision, "worker");
        await(() -> { try { JsonNode q = queue(); return q.path("messages_ready").asInt(-1) == 0 && q.path("messages_unacknowledged").asInt(-1) == 0; } catch (Exception e) { return false; } }, 30);
        assertConcurrentRoles(rollbackApi, rollbackWorker);
        String logs = checked(List.of("docker", "logs", "--tail", "200", rollbackWorker));
        assertThat(logs).contains("operation=rabbit_consume result=success");
        assertThat(sql("SELECT CONCAT(id,':',status,':',commit_sha) FROM review_task WHERE id=7001")).isEqualTo(retainedBefore);
        assertThat(JSON.readTree(sql("SELECT failure_details_json FROM review_task WHERE id=7002"))).isEqualTo(JSON.readTree(structuredBefore));
        assertThat(sql("SELECT COUNT(*) FROM review_task")).isEqualTo("2");
        assertThat(sql("SELECT COUNT(*) FROM review_execution_attempt")).isEqualTo("0");
        assertThat(sql("SELECT SUM(llm_enabled) FROM review_policy_config")).isEqualTo("0");
        for (JsonNode q : queues()) if (q.path("name").asText().endsWith(".dlq")) assertThat(q.path("messages_ready").asInt()).isZero();
        PROOF.put("rollback", Map.of("schema", schema(), "apiAndWorkerReady", true, "reviewConsumers", 1,
            "terminalDuplicateAcknowledged", true, "retainedRows", 2, "structuredJsonPreserved", true,
            "businessExecutions", 0, "llmEnabled", false));
        PROOF.put("finishedAt", Instant.now().toString()); PROOF.put("passed", true); saveProof();
    }

    private static String application(String name, String image, String revision, String role) throws Exception {
        String container = container(name, image, applicationEnvironment, List.of("--env", "REPOGUARD_RUNTIME_ROLE=" + role,
            "--env", "REPOGUARD_API_INSTANCE_COUNT=" + (role.equals("api") ? "1" : "0"),
            "--env", "SPRING_FLYWAY_ENABLED=" + role.equals("api")), List.of());
        JsonNode state = inspect("container", container);
        assertThat(state.path("Config").path("Labels").path("org.opencontainers.image.revision").asText()).isEqualTo(revision);
        await(() -> ready(container), 150);
        PROOF.put(name, Map.of("imageId", state.path("Image").asText(), "revision", revision, "role", role, "ready", true));
        saveProof();
        return container;
    }

    private static void assertConcurrentRoles(String api, String worker) throws Exception {
        assertThat(ready(api)).isTrue(); assertThat(ready(worker)).isTrue();
        assertThat(queue().path("consumers").asInt()).isEqualTo(1);
        assertThat(sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1")).isEqualTo("107");
    }

    private static boolean ready(String container) {
        try {
            for (String probe : List.of("liveness", "readiness")) {
                String body = checked(List.of("docker", "exec", container, "wget", "-qO-", "-T", "3", "http://127.0.0.1:8081/actuator/health/" + probe), Duration.ofSeconds(5));
                if (!JSON.readTree(body).path("status").asText().equals("UP")) return false;
            }
            return true;
        } catch (Exception | AssertionError e) { return false; }
    }

    private static void publishTerminalDuplicate() throws Exception {
        ConnectionFactory factory = new ConnectionFactory(); factory.setHost(rabbitHost); factory.setPort(5672);
        factory.setUsername("repoguard"); factory.setPassword(rabbitPassword); factory.setVirtualHost(VHOST);
        factory.setConnectionTimeout(5000); factory.setAutomaticRecoveryEnabled(false);
        try (var connection = factory.newConnection(); var channel = connection.createChannel()) {
            channel.confirmSelect();
            byte[] body = JSON.writeValueAsBytes(Map.of("taskId", 7001, "tenantId", 1, "organization", "rollback-fixture",
                "repository", "retained", "prNumber", 1, "commit", "a".repeat(40), "priority", 4));
            var properties = new AMQP.BasicProperties.Builder().contentType("application/json").deliveryMode(2).build();
            channel.basicPublish("", QUEUE, true, properties, body); channel.waitForConfirmsOrDie(5000);
        }
    }

    private static JsonNode queues() throws Exception {
        return JSON.readTree(checked(List.of("docker", "exec", "--user", "rabbitmq", rabbit, "rabbitmqctl", "--formatter", "json", "list_queues", "-p", VHOST,
            "name", "messages_ready", "messages_unacknowledged", "consumers"), Duration.ofSeconds(15)));
    }
    private static JsonNode queue() throws Exception {
        for (JsonNode queue : queues()) if (queue.path("name").asText().equals(QUEUE)) return queue;
        throw new AssertionError("Review queue absent on owned broker");
    }
    private static String schema() throws Exception { return sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1"); }
    private static String sql(String sql) throws Exception {
        return command(List.of("docker", "exec", "-i", mysql, "sh", "-ec",
            "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" exec mysql --protocol=TCP -h127.0.0.1 -uroot --batch --skip-column-names " + DATABASE),
            sql.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(15)).successful();
    }
    private static String secret() {
        byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
        String value = HexFormat.of().formatHex(bytes); SECRETS.add(value); return value;
    }
    private static Path environment(String name, Map<String, String> values) throws Exception {
        Path path = temporary.resolve(name + ".env");
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(path, String.join("\n", values.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList()) + "\n");
        return path;
    }
    private static String image(String name) {
        String value = System.getenv(name); assertThat(value).matches("ghcr\\.io/cocojiu/pragent-backend@sha256:[a-f0-9]{64}"); return value;
    }
    private static String revision(String name) { String value = System.getenv(name); assertThat(value).matches("[a-f0-9]{40}"); return value; }
    private static String container(String name, String image, Path env, List<String> options, List<String> args) throws Exception {
        var command = new ArrayList<>(List.of("docker", "run", "--detach", "--platform", "linux/amd64", "--name", OWNER + "-" + name,
            "--network", network, "--label", LABEL + "=" + OWNER, "--memory", "768m", "--cpus", "1",
            "--security-opt", "no-new-privileges:true", "--env-file", env.toString()));
        command.addAll(options); command.add(image); command.addAll(args);
        String id = checked(command); assertThat(id).matches("[a-f0-9]{64}"); CONTAINERS.add(id); return id;
    }
    private static JsonNode inspect(String type, String id) throws Exception {
        return JSON.readTree(checked(List.of("docker", type, "inspect", id))).get(0);
    }
    private static void stop(String container) throws Exception {
        assertOwned(container); checked(List.of("docker", "stop", "--time", "15", container), Duration.ofSeconds(25));
    }
    private static void assertOwned(String container) throws Exception {
        JsonNode state = inspect("container", container);
        assertThat(state.path("Id").asText()).isEqualTo(container);
        assertThat(state.path("Config").path("Labels").path(LABEL).asText()).isEqualTo(OWNER);
        assertThat(state.path("NetworkSettings").path("Networks").has(OWNER)).isTrue();
    }
    private static void saveProof() throws Exception { Files.writeString(EVIDENCE.resolve("result.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(PROOF)); }
    private static boolean succeeds(List<String> args) { try { return command(args, null, Duration.ofSeconds(8)).exit() == 0; } catch (Exception e) { return false; } }
    private static String checked(List<String> args) throws Exception { return checked(args, Duration.ofSeconds(30)); }
    private static String checked(List<String> args, Duration timeout) throws Exception { return command(args, null, timeout).successful(); }
    private record Result(int exit, String output) {
        String successful() { assertThat(exit).as("Owned rollback command must succeed; raw output withheld").isZero(); return output.strip(); }
    }
    private static Result command(List<String> args, byte[] input, Duration timeout) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        var output = CompletableFuture.supplyAsync(() -> {
            try { byte[] bytes = process.getInputStream().readNBytes(1024 * 1024 + 1); if (bytes.length > 1024 * 1024) throw new IllegalStateException("Output budget exceeded"); return new String(bytes, StandardCharsets.UTF_8); }
            catch (Exception e) { throw new IllegalStateException("Owned command output unavailable", e); }
        });
        try {
            if (input != null) process.getOutputStream().write(input); process.getOutputStream().close();
            assertThat(process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)).as("Owned rollback command deadline").isTrue();
            return new Result(process.exitValue(), output.get(5, TimeUnit.SECONDS));
        } finally { process.descendants().forEach(ProcessHandle::destroyForcibly); if (process.isAlive()) process.destroyForcibly(); }
    }
    private static void await(BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean()) { if (System.nanoTime() >= deadline) throw new AssertionError("Owned rollback fixture deadline"); Thread.sleep(1000); }
    }

    @AfterAll static void close() throws Exception {
        var failures = new ArrayList<Throwable>();
        for (String container : CONTAINERS.reversed()) {
            try {
                assertOwned(container);
                try {
                    String name = inspect("container", container).path("Name").asText().substring(1);
                    String logs = command(List.of("docker", "logs", "--tail", "160", container), null, Duration.ofSeconds(10)).output();
                    for (String secret : SECRETS) logs = logs.replace(secret, "[redacted]");
                    Files.writeString(EVIDENCE.resolve(name + ".log"), logs);
                } finally { checked(List.of("docker", "rm", "--force", "--volumes", container)); }
            } catch (Exception | AssertionError e) { failures.add(e); }
        }
        if (network != null) {
            assertThat(inspect("network", network).path("Labels").path(LABEL).asText()).isEqualTo(OWNER);
            checked(List.of("docker", "network", "rm", network));
        }
        assertThat(failures).as("All owned rollback containers cleaned").isEmpty();
    }
}
