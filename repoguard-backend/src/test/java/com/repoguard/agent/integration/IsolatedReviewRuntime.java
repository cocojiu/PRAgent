package com.repoguard.agent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** Owned infrastructure for opt-in hosted Linux business acceptance; never reads deployment secrets. */
@SuppressWarnings("try")
final class IsolatedReviewRuntime implements AutoCloseable {
    static final ObjectMapper JSON = new ObjectMapper();
    static final String BACKEND = System.getenv().getOrDefault("REPOGUARD_ISOLATED_BACKEND_IMAGE", "ghcr.io/cocojiu/pragent-backend@sha256:0f9136a7105ad91f2d16e58715ecac6094e400b6775187ca53e3874b6fe14194");
    static final String FRONTEND = System.getenv().getOrDefault("REPOGUARD_ISOLATED_FRONTEND_IMAGE", "ghcr.io/cocojiu/pragent-frontend@sha256:f2c386d3abd63bba2cff55042bef8d1d2b4473a101c17ea2439ed89a5cf2850b");
    static final String REVISION = System.getenv().getOrDefault("REPOGUARD_ISOLATED_REVISION", "ee9dbdcb1af74b00e2b1ac570499cd70dd46b00f");
    private static final String MYSQL = "mysql:8.0.46@sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b";
    private static final String RABBIT = "rabbitmq:3.13.7@sha256:87178a0ee3e2f52980ba356d38646ed1056705ff2d5ff281f8965456eaa0c1e3";
    private static final String CADDY = "caddy:2.11.4-alpine@sha256:5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648";
    private static final String LABEL = "com.repoguard.ci.owner";
    final String owner = "review-acceptance-" + UUID.randomUUID().toString().replace("-", "");
    final Map<String, Object> proof = new LinkedHashMap<>();
    final Path evidence = Path.of("target/isolated-review-acceptance");
    final Path temporary;
    final List<String> secrets = new ArrayList<>();
    final String adminKey = secret();
    final String password = "Ci!9-" + secret();
    final String githubToken = secret();
    final String llmApiKey = secret();
    private final List<String> containers = new ArrayList<>();
    private String network;
    String mysql;
    String rabbit;
    String api;
    String worker;
    String edge;
    String gateway;
    private String edgeIp;
    private String brokerPassword;
    String baseUrl;
    String token;
    HttpClient http;
    private Path applicationEnvironment;
    private final List<Map<String, Object>> httpRequests = new ArrayList<>();
    private String storePassword;

    IsolatedReviewRuntime(Path temporary) { this.temporary = temporary; }

    void prepare() throws Exception {
        assertThat(System.getenv("GITHUB_ACTIONS")).isEqualTo("true");
        assertThat(System.getenv("RUNNER_ENVIRONMENT")).isEqualTo("github-hosted");
        assertThat(System.getenv("GITHUB_RUN_ID")).matches("[1-9][0-9]*");
        String workflowRevision = System.getenv("GITHUB_SHA");
        String sourceRevision = System.getenv().getOrDefault("REPOGUARD_ISOLATED_SOURCE_REVISION", workflowRevision);
        assertThat(REVISION).as("Isolated acceptance must exercise the selected source revision").isEqualTo(sourceRevision);
        if (!REVISION.equals(workflowRevision)) {
            assertThat(command(List.of("git", "rev-parse", "HEAD"))).isEqualTo(workflowRevision);
            assertThat(command(List.of("git", "merge-base", REVISION, workflowRevision))).isEqualTo(REVISION);
            assertThat(command(List.of("git", "diff", "--name-only", REVISION, workflowRevision)).lines().toList())
                .as("Release source may differ only in these acceptance identity files")
                .isSubsetOf(".github/workflows/pr-quality.yml",
                    "repoguard-backend/src/test/java/com/repoguard/agent/integration/IsolatedReviewRuntime.java");
        }
        Files.createDirectories(evidence);
        long memory = Long.parseLong(command(List.of("docker", "info", "--format", "{{.MemTotal}}")));
        assertThat(memory).as("Hosted acceptance needs at least 7 GiB for six bounded services and Chromium").isGreaterThanOrEqualTo(7L * 1024 * 1024 * 1024);
        proof.put("dockerHostMemoryBytes", memory);
        proof.put("serviceLimits", Map.of("containers", 6, "memoryBytesEach", 768L * 1024 * 1024, "cpusEach", 1, "pidsEach", 256));
        proof.put("revision", REVISION); proof.put("backend", BACKEND); proof.put("frontend", FRONTEND);
        proof.put("workflowRevision", workflowRevision); proof.put("sourceRevision", sourceRevision);
        proof.put("productionWrites", 0); proof.put("realProviderCalls", 0); proof.put("naturalBusinessReplay", false);
        proof.put("runId", System.getenv("GITHUB_RUN_ID"));
        for (String image : List.of(MYSQL, RABBIT, BACKEND, FRONTEND, CADDY)) {
            if (image.matches("sha256:[a-f0-9]{64}")) {
                JsonNode local = inspect("image", image);
                assertThat(local.path("Id").asText()).isEqualTo(image);
                assertThat(local.path("Os").asText()).isEqualTo("linux");
                assertThat(local.path("Architecture").asText()).isEqualTo("amd64");
                assertThat(local.path("Config").path("Labels").path("org.opencontainers.image.revision").asText())
                    .isEqualTo(REVISION);
            } else {
                assertThat(image).matches("[a-z0-9./:_-]+@sha256:[a-f0-9]{64}");
                command(List.of("docker", "pull", "--platform", "linux/amd64", image), null, Duration.ofMinutes(4));
            }
        }
        network = command(List.of("docker", "network", "create", "--internal", "--driver", "bridge", "--label", LABEL + "=" + owner, owner));
        JsonNode state = inspect("network", network);
        assertThat(state.path("Internal").asBoolean()).isTrue();
        gateway = state.path("IPAM").path("Config").get(0).path("Gateway").asText();
        String allocatedSubnet = state.path("IPAM").path("Config").get(0).path("Subnet").asText();
        assertThat(allocatedSubnet).matches("[0-9.]+/[0-9]{1,2}");
        // Reserve a daemon-allocated, non-overlapping subnet, then declare that exact IPAM range.
        // Docker accepts a static edge address only on a network with an explicitly configured subnet.
        command(List.of("docker", "network", "rm", network)); network = null;
        network = command(List.of("docker", "network", "create", "--internal", "--driver", "bridge", "--subnet", allocatedSubnet,
            "--gateway", gateway, "--label", LABEL + "=" + owner, owner));
        assertThat(inspect("network", network).path("Internal").asBoolean()).isTrue();
        proof.put("subnet", allocatedSubnet);
        assertThat(gateway).matches("[0-9.]+");
        byte[] address = java.net.InetAddress.getByName(gateway).getAddress();
        assertThat(address.length).isEqualTo(4);
        assertThat(Byte.toUnsignedInt(address[3])).isEqualTo(1);
        address[3] = 10;
        edgeIp = java.net.InetAddress.getByAddress(address).getHostAddress();
        proof.put("edgeAddress", edgeIp);
        proof.put("internalNetwork", true);
        String databasePassword = secret(); brokerPassword = secret();
        mysql = container("mysql", MYSQL, environment("mysql", Map.of("MYSQL_ROOT_PASSWORD", secret(),
            "MYSQL_DATABASE", "review_acceptance", "MYSQL_USER", "repoguard", "MYSQL_PASSWORD", databasePassword)),
            List.of("--network-alias", "mysql"), List.of());
        await(() -> succeeds(List.of("docker", "exec", mysql, "sh", "-ec",
            "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" mysql --protocol=TCP -h127.0.0.1 -uroot -N -e 'SELECT 1'")), 120);
        rabbit = container("rabbit", RABBIT, environment("rabbit", Map.of("RABBITMQ_DEFAULT_USER", "repoguard",
            "RABBITMQ_DEFAULT_PASS", brokerPassword, "RABBITMQ_DEFAULT_VHOST", "/review-acceptance")),
            List.of("--network-alias", "rabbitmq"), List.of());
        await(() -> succeeds(List.of("docker", "exec", "--user", "rabbitmq", rabbit, "rabbitmq-diagnostics", "-q", "ping")), 90);
        var env = new LinkedHashMap<String, String>();
        env.put("REPOGUARD_REVIEW_PROGRESS_STREAM_ENABLED", "true");
        env.put("SPRING_PROFILES_ACTIVE", "prod"); env.put("SERVER_PORT", "8081");
        env.put("SPRING_DATASOURCE_URL", "jdbc:mysql://mysql:3306/review_acceptance?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC");
        env.put("SPRING_DATASOURCE_USERNAME", "repoguard"); env.put("SPRING_DATASOURCE_PASSWORD", databasePassword);
        env.put("SPRING_RABBITMQ_HOST", "rabbitmq"); env.put("SPRING_RABBITMQ_PORT", "5672");
        env.put("SPRING_RABBITMQ_USERNAME", "repoguard"); env.put("SPRING_RABBITMQ_PASSWORD", brokerPassword);
        env.put("SPRING_RABBITMQ_VIRTUAL_HOST", "/review-acceptance");
        env.put("REPOGUARD_SECURITY_ENCRYPTION_KEY", "Ci!9-" + secret()); env.put("REPOGUARD_SECURITY_ENCRYPTION_KEY_ID", "isolated-ci");
        env.put("REPOGUARD_SECURITY_ENCRYPTION_SALT", secret()); env.put("REPOGUARD_AUTH_TOKEN_SECRET", secret());
        env.put("REPOGUARD_ADMIN_API_KEY", adminKey); env.put("REPOGUARD_DEPLOYMENT_MODE", "split");
        env.put("REPOGUARD_AUTH_REGISTRATION_ENABLED", "true"); env.put("APP_CORS_ALLOWED_ORIGINS", "https://" + edgeIp);
        env.put("REPOGUARD_GITHUB_WEBHOOK_ENABLED", "false"); env.put("REPOGUARD_GITHUB_FEEDBACK_ENABLED", "false");
        env.put("REPOGUARD_GITHUB_CHECK_RUN_ENABLED", "false");
        env.put("REPOGUARD_OUTBOUND_GITHUB_ALLOWED_HOSTS", "supplier.test"); env.put("REPOGUARD_OUTBOUND_LLM_ALLOWED_HOSTS", "supplier.test");
        env.put("REPOGUARD_OUTBOUND_GITHUB_ALLOWED_PORTS", "18443"); env.put("REPOGUARD_OUTBOUND_LLM_ALLOWED_PORTS", "18443");
        env.put("REPOGUARD_OUTBOUND_PRIVATE_NETWORK_ALLOWED_HOSTS", "mysql,rabbitmq,supplier.test");
        storePassword = secret();
        SupplierStub.createCertificates(temporary, storePassword);
        command(List.of("openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-days", "2", "-subj", "/CN=" + edgeIp,
            "-addext", "subjectAltName=IP:" + edgeIp, "-keyout", temporary.resolve("edge.key").toString(), "-out", temporary.resolve("edge.crt").toString()));
        Files.setPosixFilePermissions(temporary.resolve("edge.key"), PosixFilePermissions.fromString("rw-------"));
        env.put("JAVA_TOOL_OPTIONS", "-Xms64m -Xmx384m -Djavax.net.ssl.trustStore=/ci-trust.p12 -Djavax.net.ssl.trustStorePassword=" + storePassword);
        applicationEnvironment = environment("application", env);
        proof.put("stubTrustStorePassword", "[redacted]");
        save();
    }

    String trustPassword() { return storePassword; }

    void startApplications() throws Exception {
        api = application("api", "api");
        assertThat(sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1")).isEqualTo(Integer.toString(new com.repoguard.agent.config.SchemaVersionProperties().getExpectedVersion()));
        String frontend = container("frontend", FRONTEND, environment("frontend", Map.of()), List.of("--network-alias", "frontend"), List.of());
        assertThat(inspect("container", frontend).path("Config").path("Labels").path("org.opencontainers.image.revision").asText()).isEqualTo(REVISION);
        Path repository = Path.of(System.getenv("GITHUB_WORKSPACE"));
        String product = Files.readString(repository.resolve("Caddyfile"));
        Path config = temporary.resolve("Caddyfile");
        Files.writeString(config, product.replace("{$REPOGUARD_FRONTEND_SERVER_NAME} {", "{$REPOGUARD_FRONTEND_SERVER_NAME} {\n    tls /ci-edge.crt /ci-edge.key"));
        edge = container("edge", CADDY, environment("edge", Map.of("REPOGUARD_FRONTEND_SERVER_NAME", edgeIp)),
            List.of("--entrypoint", "caddy", "--ip", edgeIp, "--volume", config + ":/etc/caddy/Caddyfile:ro",
                "--volume", temporary.resolve("edge.crt") + ":/ci-edge.crt:ro", "--volume", temporary.resolve("edge.key") + ":/ci-edge.key:ro"),
            List.of("run", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile"));
        assertThat(inspect("container", edge).path("NetworkSettings").path("Networks").path(owner).path("IPAddress").asText()).isEqualTo(edgeIp);
        byte[] certificate = Files.readAllBytes(temporary.resolve("edge.crt"));
        var keys = KeyStore.getInstance(KeyStore.getDefaultType()); keys.load(null, null);
        keys.setCertificateEntry("edge", CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(certificate)));
        var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); managers.init(keys);
        var tls = SSLContext.getInstance("TLS"); tls.init(null, managers.getTrustManagers(), null);
        http = HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(3)).build();
        // Internal bridges deliberately do not publish ports. The trusted hosted runner uses the owned bridge address.
        baseUrl = "https://" + edgeIp;
        await(() -> succeeds(List.of("docker", "exec", edge, "wget", "--no-check-certificate", "-qO-", "https://" + edgeIp + "/healthz")), 20);
        request("POST", "/api/v1/auth/register", Map.of("username", "acceptance", "email", "acceptance@example.test", "password", password, "confirmPassword", password), null);
        sql("UPDATE user_account SET role='ADMIN' WHERE username='acceptance'");
        token = request("POST", "/api/v1/auth/login", Map.of("account", "acceptance", "password", password), null).path("accessToken").asText();
        assertThat(token).isNotBlank(); secrets.add(token);
        request("PUT", "/api/v1/config/integrations/github", Map.of("baseUrl", "https://supplier.test:18443", "token", githubToken, "defaultOwner", "fixture", "defaultRepo", "sample"), token);
        configureReviewPolicy(180, true);
        worker = application("worker", "worker");
        proof.put("schema", new com.repoguard.agent.config.SchemaVersionProperties().getExpectedVersion()); proof.put("rolesSimultaneouslyReady", true); save();
    }

    void configureReviewPolicy(int seconds, boolean fallback) throws Exception {
        var policy = new LinkedHashMap<String, Object>();
        policy.put("llmEnabled", true); policy.put("llmProvider", "openai-compatible"); policy.put("modelName", "isolated-stub");
        policy.put("baseUrl", "https://supplier.test:18443/v1"); policy.put("apiKey", llmApiKey); policy.put("timeoutSeconds", seconds);
        policy.put("temperature", 0); policy.put("maxTokens", 2048); policy.put("fallbackToRules", fallback); policy.put("workerConcurrency", 1);
        policy.put("chunkFileThreshold", 10); policy.put("chunkLineThreshold", 1000); policy.put("chunkMaxFiles", 10); policy.put("chunkMaxLines", 1000);
        policy.put("inputTokenPricePerMillion", 0); policy.put("outputTokenPricePerMillion", 0);
        request("PUT", "/api/v1/config/review-policy", policy, token);
    }

    private String application(String name, String role) throws Exception {
        String id = container(name, BACKEND, applicationEnvironment, List.of("--network-alias", name.equals("api") ? "backend" : "worker",
            "--add-host", "supplier.test:" + gateway, "--volume", temporary.resolve("trust.p12") + ":/ci-trust.p12:ro",
            "--env", "REPOGUARD_RUNTIME_ROLE=" + role, "--env", "REPOGUARD_API_INSTANCE_COUNT=" + (role.equals("api") ? "1" : "0"),
            "--env", "SPRING_FLYWAY_ENABLED=" + role.equals("api")), List.of());
        assertThat(inspect("container", id).path("Config").path("Labels").path("org.opencontainers.image.revision").asText()).isEqualTo(REVISION);
        await(() -> succeeds(List.of("docker", "exec", id, "wget", "-qO-", "-T", "3", "http://127.0.0.1:8081/actuator/health/readiness")), 150);
        return id;
    }

    JsonNode request(String method, String path, Object body, String bearer) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(Duration.ofSeconds(15)).header("Content-Type", "application/json");
        if (bearer != null) request.header("Authorization", "Bearer " + bearer).header("X-RepoGuard-Admin-Key", adminKey);
        request.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
        long started = System.nanoTime();
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        httpRequests.add(Map.of("method", method, "path", path, "status", response.statusCode(),
            "traceId", response.headers().firstValue("X-Trace-Id").orElse(""), "durationNanos", System.nanoTime() - started));
        Files.writeString(evidence.resolve("http-request-metadata.json"), JSON.writeValueAsString(httpRequests));
        assertThat(response.statusCode()).as("HTTP %s %s; response body withheld", method, path).isEqualTo(200);
        JsonNode root = JSON.readTree(response.body());
        assertThat(root.path("success").asBoolean()).as("Application response code %s", root.path("code").asText()).isTrue();
        return root.path("data");
    }

    void enableSqlObservation() throws Exception {
        sql("UPDATE performance_schema.setup_consumers SET ENABLED='YES' WHERE NAME='events_statements_history_long'");
    }

    void captureSqlCounters(String name) throws Exception {
        String digests = sql("SELECT DIGEST,DIGEST_TEXT,COUNT_STAR,SUM_ROWS_EXAMINED,SUM_ROWS_SENT,SUM_TIMER_WAIT "
            + "FROM performance_schema.events_statements_summary_by_digest WHERE SCHEMA_NAME='review_acceptance' "
            + "AND DIGEST_TEXT LIKE 'SELECT%' AND (DIGEST_TEXT LIKE '%review_task%' OR DIGEST_TEXT LIKE '%review_timeline%') "
            + "AND DIGEST_TEXT NOT LIKE '%performance_schema%' ORDER BY DIGEST LIMIT 100");
        Files.writeString(evidence.resolve(name + "-sql-digests.tsv"), digests);
    }

    void captureReadCounters(String name, long task) throws Exception {
        captureSqlCounters(name);
        var args = new ArrayList<>(List.of("docker", "stats", "--no-stream", "--format", "{{json .}}")); args.addAll(containers);
        Files.writeString(evidence.resolve(name + "-container-stats.jsonl"), command(args));
        String apiIp = inspect("container", api).path("NetworkSettings").path("Networks").path(owner).path("IPAddress").asText();
        var metrics = http.send(HttpRequest.newBuilder(URI.create("http://" + apiIp + ":8081/actuator/prometheus"))
            .timeout(Duration.ofSeconds(5)).header("Authorization", "Bearer " + token).header("X-RepoGuard-Admin-Key", adminKey).GET().build(), HttpResponse.BodyHandlers.ofString());
        String boundedMetrics = metrics.statusCode() == 200 ? String.join("\n", metrics.body().lines()
            .filter(line -> line.matches("^(jvm_gc_|process_cpu_|system_cpu_|jvm_memory_).*")).limit(200).toList())
            : "unavailable HTTP " + metrics.statusCode();
        Files.writeString(evidence.resolve(name + "-jvm-metrics.txt"), boundedMetrics);
        String actual = sql("SELECT SQL_TEXT FROM performance_schema.events_statements_history_long WHERE CURRENT_SCHEMA='review_acceptance' "
            + "AND DIGEST_TEXT LIKE 'SELECT%FROM%review_timeline%' AND DIGEST_TEXT NOT LIKE '%performance_schema%' "
            + "AND SQL_TEXT LIKE '%" + task + "%' ORDER BY EVENT_ID DESC LIMIT 1");
        if (!actual.isBlank() && !actual.contains("?") && !actual.contains(";") && actual.stripLeading().toUpperCase(java.util.Locale.ROOT).startsWith("SELECT")) {
            Files.writeString(evidence.resolve(name + "-actual-timeline.sql.txt"), actual);
            Files.writeString(evidence.resolve(name + "-timeline-explain-analyze.txt"), sql("EXPLAIN ANALYZE " + actual));
        }
    }

    void publishTerminalDuplicate(long task) throws Exception {
        var factory = new com.rabbitmq.client.ConnectionFactory();
        factory.setHost(inspect("container", rabbit).path("NetworkSettings").path("Networks").path(owner).path("IPAddress").asText());
        factory.setPort(5672); factory.setUsername("repoguard"); factory.setPassword(brokerPassword);
        factory.setVirtualHost("/review-acceptance"); factory.setConnectionTimeout(5000); factory.setAutomaticRecoveryEnabled(false);
        try (var connection = factory.newConnection(); var channel = connection.createChannel()) {
            channel.confirmSelect();
            byte[] payload = JSON.writeValueAsBytes(Map.of("taskId", task, "tenantId", 1, "organization", "fixture", "repository", "sample",
                "prNumber", 1, "commit", SupplierStub.COMMIT, "priority", 4));
            channel.basicPublish("", "repoguard.review.queue.v3", true,
                new com.rabbitmq.client.AMQP.BasicProperties.Builder().contentType("application/json").deliveryMode(2).build(), payload);
            channel.waitForConfirmsOrDie(5000);
        }
        await(() -> {
            try { var queue = reviewQueue(); return queue.path("messages_ready").asInt(-1) == 0 && queue.path("messages_unacknowledged").asInt(-1) == 0; }
            catch (Exception e) { return false; }
        }, 30);
        assertThat(reviewQueue().path("consumers").asInt()).isEqualTo(1);
    }

    private JsonNode reviewQueue() throws Exception {
        var queues = JSON.readTree(command(List.of("docker", "exec", "--user", "rabbitmq", rabbit, "rabbitmqctl", "--formatter", "json", "list_queues",
            "-p", "/review-acceptance", "name", "messages_ready", "messages_unacknowledged", "consumers")));
        for (var queue : queues) if (queue.path("name").asText().equals("repoguard.review.queue.v3")) return queue;
        throw new AssertionError("Owned review queue absent");
    }

    String sql(String query) throws Exception {
        return command(List.of("docker", "exec", "-i", mysql, "sh", "-ec", "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" exec mysql --protocol=TCP -h127.0.0.1 -uroot --batch --raw --skip-column-names review_acceptance"),
            query.getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(30));
    }
    void save() throws Exception { Files.writeString(evidence.resolve("result.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(proof)); }
    String secret() { byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes); String value = HexFormat.of().formatHex(bytes); secrets.add(value); return value; }
    Path environment(String name, Map<String, String> values) throws Exception {
        Path path = temporary.resolve(name + ".env");
        Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        Files.writeString(path, String.join("\n", values.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).toList()) + "\n"); return path;
    }
    private String container(String name, String image, Path env, List<String> options, List<String> arguments) throws Exception {
        var command = new ArrayList<>(List.of("docker", "run", "--detach", "--platform", "linux/amd64", "--name", owner + "-" + name,
            "--network", network, "--label", LABEL + "=" + owner, "--memory", "768m", "--cpus", "1", "--pids-limit", "256",
            "--security-opt", "no-new-privileges:true", "--env-file", env.toString()));
        command.addAll(options); command.add(image); command.addAll(arguments);
        String id = command(command); assertThat(id).matches("[a-f0-9]{64}"); containers.add(id); return id;
    }
    JsonNode inspect(String type, String id) throws Exception { return JSON.readTree(command(List.of("docker", type, "inspect", id))).get(0); }
    static void await(BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean()) { if (System.nanoTime() >= deadline) throw new AssertionError("Isolated business acceptance deadline"); Thread.sleep(250); }
    }
    boolean succeeds(List<String> args) { try { command(args, null, Duration.ofSeconds(8)); return true; } catch (Exception | AssertionError e) { return false; } }
    String command(List<String> args) throws Exception { return command(args, null, Duration.ofSeconds(40)); }
    String command(List<String> args, byte[] input, Duration timeout) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        var output = CompletableFuture.supplyAsync(() -> {
            try { return process.getInputStream().readNBytes(1024 * 1024 + 1); } catch (Exception e) { throw new IllegalStateException(e); }
        });
        try {
            if (input != null) process.getOutputStream().write(input); process.getOutputStream().close();
            assertThat(process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)).as("Owned command deadline").isTrue();
            byte[] bytes = output.get(5, TimeUnit.SECONDS); assertThat(bytes.length).isLessThanOrEqualTo(1024 * 1024);
            if (process.exitValue() != 0) {
                String diagnostic = new String(bytes, StandardCharsets.UTF_8);
                for (String value : secrets) diagnostic = diagnostic.replace(value, "[redacted]");
                diagnostic = diagnostic.replaceAll("(?i)(bearer\\s+)[^\\s\"]+", "$1[redacted]");
                Files.writeString(evidence.resolve("last-owned-command-failure.log"), diagnostic.substring(0, Math.min(8192, diagnostic.length())));
            }
            assertThat(process.exitValue()).as("Owned command exit; redacted diagnostic saved separately").isZero();
            return new String(bytes, StandardCharsets.UTF_8).strip();
        } finally { process.descendants().forEach(ProcessHandle::destroyForcibly); if (process.isAlive()) process.destroyForcibly(); }
    }
    @Override public void close() throws Exception {
        var failures = new ArrayList<Throwable>();
        if (http != null) http.shutdownNow();
        // docker run can create a container before failing to start its entrypoint.
        // Discover those partial starts by exact owner label so cleanup includes them.
        if (network != null) {
            String owned = command(List.of("docker", "ps", "--all", "--no-trunc", "--filter", "label=" + LABEL + "=" + owner, "--format", "{{.ID}}"));
            for (String id : owned.lines().filter(value -> !value.isBlank()).toList()) {
                assertThat(id).matches("[a-f0-9]{64}");
                if (!containers.contains(id)) containers.add(id);
            }
        }
        for (String id : containers.reversed()) {
            try {
                var state = inspect("container", id);
                assertThat(state.path("Config").path("Labels").path(LABEL).asText()).isEqualTo(owner);
                assertThat(state.path("HostConfig").path("NetworkMode").asText()).isIn(network, owner);
                String name = state.path("Name").asText().substring(1);
                try {
                    String logs = command(List.of("docker", "logs", "--tail", "120", id));
                    for (String value : secrets) logs = logs.replace(value, "[redacted]");
                    logs = logs.replaceAll("(?i)(bearer\\s+)[^\\s\"]+", "$1[redacted]");
                    Files.writeString(evidence.resolve(name + ".log"), logs);
                } finally { command(List.of("docker", "rm", "--force", "--volumes", id)); }
            } catch (Exception | AssertionError e) { failures.add(e); }
        }
        if (network != null) {
            assertThat(inspect("network", network).path("Labels").path(LABEL).asText()).isEqualTo(owner);
            command(List.of("docker", "network", "rm", network));
        }
        proof.put("cleanupPassed", failures.isEmpty()); save();
        assertThat(failures).as("All owned containers cleaned").isEmpty();
    }
}
