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
    static final String BACKEND = "ghcr.io/cocojiu/pragent-backend@sha256:be3787ca75026a10bc1261bc52a5ecebe27f588bfb8a30ea47fb4cd351d2ebea";
    static final String FRONTEND = "ghcr.io/cocojiu/pragent-frontend@sha256:bcc92b4d715a2575403f31176b97c11fbf7c878e50eb49ef389c673ef2fbf876";
    static final String REVISION = "8494258e5bba2c6ecbc4a5f490bcfda10b646bed";
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
    private final List<String> containers = new ArrayList<>();
    private String network;
    String mysql;
    String rabbit;
    String api;
    String worker;
    String edge;
    String gateway;
    String baseUrl;
    String token;
    HttpClient http;
    private Path applicationEnvironment;
    private String storePassword;

    IsolatedReviewRuntime(Path temporary) { this.temporary = temporary; }

    void prepare() throws Exception {
        assertThat(System.getenv("GITHUB_ACTIONS")).isEqualTo("true");
        assertThat(System.getenv("RUNNER_ENVIRONMENT")).isEqualTo("github-hosted");
        assertThat(System.getenv("GITHUB_RUN_ID")).matches("[1-9][0-9]*");
        Files.createDirectories(evidence);
        long memory = Long.parseLong(command(List.of("docker", "info", "--format", "{{.MemTotal}}")));
        assertThat(memory).as("Hosted acceptance needs at least 5 GiB for five bounded services and Chromium").isGreaterThanOrEqualTo(5L * 1024 * 1024 * 1024);
        proof.put("dockerHostMemoryBytes", memory);
        proof.put("serviceLimits", Map.of("containers", 5, "memoryBytesEach", 768L * 1024 * 1024, "cpusEach", 1, "pidsEach", 256));
        proof.put("revision", REVISION); proof.put("backend", BACKEND); proof.put("frontend", FRONTEND);
        proof.put("productionWrites", 0); proof.put("realProviderCalls", 0); proof.put("naturalBusinessReplay", false);
        proof.put("runId", System.getenv("GITHUB_RUN_ID"));
        for (String image : List.of(MYSQL, RABBIT, BACKEND, FRONTEND, CADDY)) {
            command(List.of("docker", "pull", "--platform", "linux/amd64", image), null, Duration.ofMinutes(4));
        }
        network = command(List.of("docker", "network", "create", "--internal", "--driver", "bridge", "--label", LABEL + "=" + owner, owner));
        JsonNode state = inspect("network", network);
        assertThat(state.path("Internal").asBoolean()).isTrue();
        gateway = state.path("IPAM").path("Config").get(0).path("Gateway").asText();
        assertThat(gateway).matches("[0-9.]+");
        proof.put("internalNetwork", true);
        String databasePassword = secret(); String brokerPassword = secret();
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
        env.put("REPOGUARD_AUTH_REGISTRATION_ENABLED", "true"); env.put("APP_CORS_ALLOWED_ORIGINS", "https://localhost");
        env.put("REPOGUARD_GITHUB_WEBHOOK_ENABLED", "false"); env.put("REPOGUARD_GITHUB_FEEDBACK_ENABLED", "false");
        env.put("REPOGUARD_GITHUB_CHECK_RUN_ENABLED", "false");
        env.put("REPOGUARD_OUTBOUND_GITHUB_ALLOWED_HOSTS", "supplier.test"); env.put("REPOGUARD_OUTBOUND_LLM_ALLOWED_HOSTS", "supplier.test");
        env.put("REPOGUARD_OUTBOUND_GITHUB_ALLOWED_PORTS", "18443"); env.put("REPOGUARD_OUTBOUND_LLM_ALLOWED_PORTS", "18443");
        env.put("REPOGUARD_OUTBOUND_PRIVATE_NETWORK_ALLOWED_HOSTS", "mysql,rabbitmq,supplier.test");
        storePassword = secret();
        SupplierStub.createCertificates(temporary, storePassword);
        env.put("JAVA_TOOL_OPTIONS", "-Xms64m -Xmx384m -Djavax.net.ssl.trustStore=/ci-trust.p12 -Djavax.net.ssl.trustStorePassword=" + storePassword);
        applicationEnvironment = environment("application", env);
        proof.put("stubTrustStorePassword", "[redacted]");
        save();
    }

    String trustPassword() { return storePassword; }

    void startApplications() throws Exception {
        api = application("api", "api");
        assertThat(sql("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=1")).isEqualTo("108");
        String frontend = container("frontend", FRONTEND, environment("frontend", Map.of()), List.of("--network-alias", "frontend"), List.of());
        assertThat(inspect("container", frontend).path("Config").path("Labels").path("org.opencontainers.image.revision").asText()).isEqualTo(REVISION);
        Path repository = Path.of(System.getenv("GITHUB_WORKSPACE"));
        String product = Files.readString(repository.resolve("Caddyfile"));
        Path config = temporary.resolve("Caddyfile");
        Files.writeString(config, product.replace("{$REPOGUARD_FRONTEND_SERVER_NAME} {", "{$REPOGUARD_FRONTEND_SERVER_NAME} {\n    tls internal"));
        edge = container("edge", CADDY, environment("edge", Map.of("REPOGUARD_FRONTEND_SERVER_NAME", "localhost")),
            List.of("--publish", "127.0.0.1::443", "--volume", config + ":/etc/caddy/Caddyfile:ro"),
            List.of("run", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile"));
        await(() -> succeeds(List.of("docker", "exec", edge, "test", "-f", "/data/caddy/pki/authorities/local/root.crt")), 30);
        byte[] certificate = command(List.of("docker", "exec", edge, "cat", "/data/caddy/pki/authorities/local/root.crt")).getBytes(StandardCharsets.US_ASCII);
        var keys = KeyStore.getInstance(KeyStore.getDefaultType()); keys.load(null, null);
        keys.setCertificateEntry("edge", CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(certificate)));
        var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); managers.init(keys);
        var tls = SSLContext.getInstance("TLS"); tls.init(null, managers.getTrustManagers(), null);
        http = HttpClient.newBuilder().sslContext(tls).connectTimeout(Duration.ofSeconds(3)).build();
        String binding = command(List.of("docker", "port", edge, "443/tcp"));
        assertThat(binding).matches("127\\.0\\.0\\.1:[1-9][0-9]*");
        baseUrl = "https://localhost:" + binding.substring(binding.lastIndexOf(':') + 1);
        request("POST", "/api/v1/auth/register", Map.of("username", "acceptance", "email", "acceptance@example.test", "password", password, "confirmPassword", password), null);
        sql("UPDATE user_account SET role='ADMIN' WHERE username='acceptance'");
        token = request("POST", "/api/v1/auth/login", Map.of("account", "acceptance", "password", password), null).path("accessToken").asText();
        assertThat(token).isNotBlank(); secrets.add(token);
        request("PUT", "/api/v1/config/integrations/github", Map.of("baseUrl", "https://supplier.test:18443", "token", secret(), "defaultOwner", "fixture", "defaultRepo", "sample"), token);
        var policy = new LinkedHashMap<String, Object>();
        policy.put("llmEnabled", true); policy.put("llmProvider", "openai-compatible"); policy.put("modelName", "isolated-stub");
        policy.put("baseUrl", "https://supplier.test:18443/v1"); policy.put("apiKey", secret()); policy.put("timeoutSeconds", 120);
        policy.put("temperature", 0); policy.put("maxTokens", 2048); policy.put("fallbackToRules", true); policy.put("workerConcurrency", 1);
        policy.put("chunkFileThreshold", 10); policy.put("chunkLineThreshold", 1000); policy.put("chunkMaxFiles", 10); policy.put("chunkMaxLines", 1000);
        policy.put("inputTokenPricePerMillion", 0); policy.put("outputTokenPricePerMillion", 0);
        request("PUT", "/api/v1/config/review-policy", policy, token);
        worker = application("worker", "worker");
        proof.put("schema", 108); proof.put("rolesSimultaneouslyReady", true); save();
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
        var response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("HTTP %s %s; response body withheld", method, path).isEqualTo(200);
        JsonNode root = JSON.readTree(response.body());
        assertThat(root.path("success").asBoolean()).as("Application response code %s", root.path("code").asText()).isTrue();
        return root.path("data");
    }

    String sql(String query) throws Exception {
        return command(List.of("docker", "exec", "-i", mysql, "sh", "-ec", "MYSQL_PWD=\"$MYSQL_ROOT_PASSWORD\" exec mysql --protocol=TCP -h127.0.0.1 -uroot --batch --skip-column-names review_acceptance"),
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
    static boolean succeeds(List<String> args) { try { command(args, null, Duration.ofSeconds(8)); return true; } catch (Exception | AssertionError e) { return false; } }
    static String command(List<String> args) throws Exception { return command(args, null, Duration.ofSeconds(40)); }
    static String command(List<String> args, byte[] input, Duration timeout) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        var output = CompletableFuture.supplyAsync(() -> {
            try { return process.getInputStream().readNBytes(1024 * 1024 + 1); } catch (Exception e) { throw new IllegalStateException(e); }
        });
        try {
            if (input != null) process.getOutputStream().write(input); process.getOutputStream().close();
            assertThat(process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)).as("Owned command deadline").isTrue();
            byte[] bytes = output.get(5, TimeUnit.SECONDS); assertThat(bytes.length).isLessThanOrEqualTo(1024 * 1024);
            assertThat(process.exitValue()).as("Owned command exit; raw output withheld").isZero();
            return new String(bytes, StandardCharsets.UTF_8).strip();
        } finally { process.descendants().forEach(ProcessHandle::destroyForcibly); if (process.isAlive()) process.destroyForcibly(); }
    }
    @Override public void close() throws Exception {
        var failures = new ArrayList<Throwable>();
        if (http != null) http.shutdownNow();
        for (String id : containers.reversed()) {
            try {
                var state = inspect("container", id);
                assertThat(state.path("Config").path("Labels").path(LABEL).asText()).isEqualTo(owner);
                assertThat(state.path("NetworkSettings").path("Networks").has(owner)).isTrue();
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
