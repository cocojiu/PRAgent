package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.config.JacksonConfig;
import com.sun.net.httpserver.HttpsServer;
import com.sun.net.httpserver.HttpsConfigurator;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

class LocalPreflightCliOutputTest {
    private static final ObjectMapper JSON = new JacksonConfig().objectMapper();
    private static final String CREDENTIAL = "rgpf.local_fixture.signature";
    private static final String MESSAGE = "中文🙂\u001b[2J\u202ehidden\nforged\u009bcontrol";
    private static SSLContext tls;
    @TempDir static Path certificates;
    @TempDir Path temporary;
    private HttpsServer server;
    private ExecutorService executor;
    private Path repository;
    private final AtomicInteger requests = new AtomicInteger();
    private int status = 200;
    private CountDownLatch concurrentRequests;

    @BeforeAll static void certificate() throws Exception {
        String password = UUID.randomUUID().toString();
        Path keytool = Path.of(System.getProperty("java.home"), "bin", windows() ? "keytool.exe" : "keytool");
        run(certificates, keytool.toString(), "-genkeypair", "-alias", "fixture", "-keyalg", "RSA",
            "-keysize", "2048", "-validity", "1", "-dname", "CN=localhost",
            "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-storetype", "PKCS12",
            "-keystore", certificates.resolve("fixture.p12").toString(), "-storepass", password, "-noprompt");
        run(certificates, keytool.toString(), "-exportcert", "-rfc", "-alias", "fixture",
            "-keystore", certificates.resolve("fixture.p12").toString(), "-storepass", password,
            "-file", certificates.resolve("fixture.pem").toString());
        var store = KeyStore.getInstance("PKCS12");
        try (var stream = Files.newInputStream(certificates.resolve("fixture.p12"))) { store.load(stream, password.toCharArray()); }
        var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(store, password.toCharArray());
        tls = SSLContext.getInstance("TLS"); tls.init(managers.getKeyManagers(), null, null);
    }

    @BeforeEach void setup() throws Exception {
        repository = temporary.resolve("repository"); Files.createDirectories(repository);
        run(repository, "git", "init", "--quiet");
        run(repository, "git", "config", "user.name", "local fixture");
        run(repository, "git", "config", "user.email", "fixture@fixture.invalid");
        Files.writeString(repository.resolve("Example.java"), "class Example {}\n");
        run(repository, "git", "add", "Example.java");
        run(repository, "git", "commit", "--quiet", "-m", "fixture");
        Files.writeString(repository.resolve("Example.java"), "class Example { void call() throws Exception { Thread.sleep(1); } }\n");
        server = HttpsServer.create(new InetSocketAddress("localhost", 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(tls));
        executor = Executors.newCachedThreadPool(); server.setExecutor(executor);
        server.createContext("/api/v1/preflight/preview", exchange -> {
            try {
                requests.incrementAndGet();
                byte[] input = exchange.getRequestBody().readNBytes(3 * 1024 * 1024 + 1);
                if (concurrentRequests != null) {
                    concurrentRequests.countDown();
                    try { concurrentRequests.await(10, TimeUnit.SECONDS); }
                    catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
                byte[] body;
                if (status == 200) {
                    String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
                    body = JSON.writeValueAsBytes(Map.of("success", true, "data", Map.of(
                        "scope", "ONLINE_PREFLIGHT", "repository", "fixture/repo", "requestSha256", sha,
                        "productionPolicyVerified", true, "policyFingerprint", "0".repeat(64),
                        "scanComplete", true, "llmExecuted", false, "findings", List.of(Map.of(
                            "severity", "LOW", "ruleId", "FIXTURE", "filePath", "Example.java",
                            "message", MESSAGE, "recommendation", "Review locally", "fingerprint", "fixture", "lineNumber", 1)))));
                } else {
                    body = (CREDENTIAL + "\u001b[2Jprivate error body").getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Location", "https://localhost:9/do-not-forward");
                }
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
            } catch (Exception failure) {
                exchange.close();
            } finally { exchange.close(); }
        });
        server.start();
    }

    @AfterEach void stop() throws Exception {
        if (server != null) server.stop(0);
        if (executor != null) { executor.shutdownNow(); executor.awaitTermination(5, TimeUnit.SECONDS); }
    }

    @Test void textEscapesTerminalAndBidiControlsButPreservesReadableUnicode() throws Exception {
        var result = cli("text", List.of());
        assertThat(result.code()).isZero();
        assertThat(result.out()).contains("中文🙂\\x1b[2J\\u202ehidden\\x0aforged\\x9bcontrol")
            .doesNotContain("\u001b", "\u202e", "\u009b", CREDENTIAL);
        assertThat(result.error()).isEmpty();
        assertThat(requests.get()).isEqualTo(1);
    }

    @ParameterizedTest @ValueSource(strings = {"json", "sarif"})
    void machineReportsEscapeDisplayControlsAndRetainOriginalFieldValues(String format) throws Exception {
        var result = cli(format, List.of());
        assertThat(result.code()).isZero();
        assertThat(result.out()).doesNotContain("\u001b", "\u202e", "\u009b", CREDENTIAL);
        var report = JSON.readTree(result.out());
        JsonNode findings = format.equals("json") ? report.path("findings") : report.path("runs").get(0).path("results");
        JsonNode message = findings.get(0).path("message");
        assertThat(format.equals("json") ? message.asText() : message.path("text").asText()).isEqualTo(MESSAGE);
    }

    @Test void existingReportIsRefusedBeforeAnyUploadAndPreservedByteForByte() throws Exception {
        Path target = temporary.resolve("report.json"); Files.writeString(target, "existing report");
        var result = cli("json", List.of("--output", target.toString()));
        assertThat(result.code()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(JSON.readTree(result.error()).path("error").path("code").asText()).isEqualTo("report_already_exists");
        assertThat(Files.readString(target)).isEqualTo("existing report");
        assertThat(requests.get()).isZero();
    }

    @Test void competingCliPublishersProduceOneCompleteReportAndNeverOverwrite() throws Exception {
        concurrentRequests = new CountDownLatch(2);
        Path target = temporary.resolve("report.json");
        var first = start("json", List.of("--output", target.toString()));
        var second = start("json", List.of("--output", target.toString()));
        var a = finish(first); var b = finish(second);
        assertThat(List.of(a.code(), b.code())).containsExactlyInAnyOrder(0, 2);
        var rejected = a.code() == 2 ? a : b;
        assertThat(JSON.readTree(rejected.error()).path("error").path("code").asText()).isEqualTo("report_already_exists");
        assertThat(JSON.readTree(Files.readAllBytes(target)).path("findings").get(0).path("message").asText()).isEqualTo(MESSAGE);
        assertThat(requests.get()).isEqualTo(2);
        try (var entries = Files.list(temporary)) {
            assertThat(entries.map(p -> p.getFileName().toString()).filter(p -> p.startsWith(".repoguard-report-")).toList()).isEmpty();
        }
    }

    @ParameterizedTest @CsvSource({
        "301,preflight_redirect_refused", "400,preflight_request_rejected",
        "401,preflight_credential_rejected", "403,preflight_permission_denied",
        "404,preflight_service_unavailable", "405,preflight_service_unavailable",
        "409,preflight_version_or_request_conflict", "413,preflight_request_budget_exceeded",
        "415,preflight_request_rejected", "422,preflight_request_rejected",
        "429,preflight_rate_limited", "500,preflight_service_failure",
        "502,preflight_service_failure", "503,preflight_service_unavailable"
    })
    void actualHttpsErrorsHaveStableSafeCodesAndNeverRetry(int responseStatus, String code) throws Exception {
        status = responseStatus;
        var result = cli("json", List.of());
        assertThat(result.code()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.error()).doesNotContain(CREDENTIAL, "\u001b", "private error body");
        var diagnostic = JSON.readTree(result.error());
        assertThat(diagnostic.path("error").path("code").asText()).isEqualTo(code);
        assertThat(diagnostic.path("error").path("message").asText()).isNotBlank();
        assertThat(diagnostic.path("exitCode").asInt()).isEqualTo(2);
        assertThat(requests.get()).isEqualTo(1);
    }

    @Test void invalidArgumentsNeverEchoCredentialOrTerminalControls() throws Exception {
        var result = cli("json", List.of("--unknown=" + CREDENTIAL + "\u001b[2J"));
        assertThat(result.code()).isEqualTo(2);
        assertThat(result.out()).isEmpty();
        assertThat(result.error()).doesNotContain(CREDENTIAL, "\u001b");
        assertThat(JSON.readTree(result.error()).path("error").path("code").asText()).isEqualTo("invalid_cli_arguments");
        assertThat(requests.get()).isZero();
    }

    @ParameterizedTest @ValueSource(strings = {"text", "json", "sarif"})
    void cleanScopeFinishesWithOpenCredentialInputAndNoHttpsRequest(String format) throws Exception {
        Files.writeString(repository.resolve("Example.java"), "class Example {}\n");
        var child = start(format, List.of("--llm", "--fail-on", "INFO"), false);
        Result result;
        try {
            assertThat(child.process().waitFor(5, TimeUnit.SECONDS)).as("Empty diff must not read open credential stdin").isTrue();
            result = finish(child);
        } finally { if (child.process().isAlive()) child.process().destroyForcibly(); }
        assertThat(result.code()).isZero();
        assertThat(result.error()).isEmpty();
        assertThat(requests.get()).isZero();
        if (format.equals("text")) {
            assertThat(result.out()).contains("execution skipped", "production policy not verified");
        } else {
            var report = JSON.readTree(result.out());
            var properties = format.equals("json") ? report : report.path("runs").get(0).path("properties");
            assertThat(properties.path("scope").asText()).isEqualTo("LOCAL_NO_CHANGES");
            assertThat(properties.path("executionSkipped").asBoolean()).isTrue();
            assertThat(properties.path("productionPolicyVerified").asBoolean()).isFalse();
            assertThat(properties.path("llmExecuted").asBoolean()).isFalse();
            assertThat(properties.has("onlineTransport")).isFalse();
        }
    }

    private Result cli(String format, List<String> extra) throws Exception { return finish(start(format, extra)); }

    private Child start(String format, List<String> extra) throws Exception { return start(format, extra, true); }

    private Child start(String format, List<String> extra, boolean provideCredential) throws Exception {
        Path cli = Path.of("..", "scripts", "repoguard-review.py").toAbsolutePath().normalize();
        String python = System.getenv().getOrDefault("REPOGUARD_PREFLIGHT_PYTHON", windows() ? "python" : "python3");
        var command = new ArrayList<>(List.of(python, "-B", "-X", "utf8", cli.toString(),
            "--repo", repository.toString(), "--base", "HEAD",
            "--api-url", "https://localhost:" + server.getAddress().getPort(), "--repository", "fixture/repo",
            "--preflight-token-stdin", "--format", format));
        command.addAll(extra);
        Path out = temporary.resolve(UUID.randomUUID() + ".out");
        Path error = temporary.resolve(UUID.randomUUID() + ".err");
        var builder = new ProcessBuilder(command).directory(repository.toFile()).redirectOutput(out.toFile()).redirectError(error.toFile());
        environment(builder);
        builder.environment().putAll(Map.of("SSL_CERT_FILE", certificates.resolve("fixture.pem").toString(),
            "NO_PROXY", "localhost,127.0.0.1", "no_proxy", "localhost,127.0.0.1"));
        var process = builder.start();
        if (provideCredential) {
            try (var input = process.getOutputStream()) { input.write((CREDENTIAL + "\n").getBytes(StandardCharsets.UTF_8)); }
            catch (java.io.IOException closed) { if (process.isAlive()) throw closed; }
        }
        return new Child(process, out, error);
    }

    private Result finish(Child child) throws Exception {
        try {
            assertThat(child.process().waitFor(55, TimeUnit.SECONDS)).as("Actual CLI bounded execution").isTrue();
            return new Result(child.process().exitValue(), Files.readString(child.out()), Files.readString(child.error()));
        } finally { if (child.process().isAlive()) child.process().destroyForcibly(); }
    }

    private static void run(Path cwd, String... args) throws Exception {
        var builder = new ProcessBuilder(args).directory(cwd.toFile());
        environment(builder);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD).redirectError(ProcessBuilder.Redirect.DISCARD);
        var process = builder.start();
        try {
            assertThat(process.waitFor(20, TimeUnit.SECONDS)).as("Local fixture preparation").isTrue();
            assertThat(process.exitValue()).as("Local fixture command exit; arguments withheld").isZero();
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    private static void environment(ProcessBuilder builder) {
        builder.environment().putAll(Map.of("GIT_CONFIG_NOSYSTEM", "1", "GIT_CONFIG_GLOBAL", windows() ? "NUL" : "/dev/null"));
        builder.environment().remove("GIT_DIR"); builder.environment().remove("GIT_WORK_TREE");
    }

    private static boolean windows() { return System.getProperty("os.name").startsWith("Windows"); }
    private record Child(Process process, Path out, Path error) {}
    private record Result(int code, String out, String error) {}
}
