package com.repoguard.agent.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.authentication.AuthenticatedPrincipal;
import com.repoguard.agent.authentication.RequestAuthenticationAttributes;
import com.repoguard.agent.common.GlobalExceptionHandler;
import com.repoguard.agent.controller.ReviewProgressController;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.entity.ReviewTimeline;
import com.repoguard.agent.entity.UserAccount;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.mapper.ReviewTimelineMapper;
import com.repoguard.agent.review.progress.ReviewProgressStreamService;
import com.repoguard.agent.security.AuthAccountCache;
import com.repoguard.agent.tenancy.TenantProperties;
import com.repoguard.agent.tenancy.TenantResolutionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.CertificateFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import org.apache.catalina.startup.Tomcat;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.servlet.DispatcherServlet;
import org.springframework.validation.beanvalidation.MethodValidationPostProcessor;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Real transport and product proxy configuration; authentication and repositories are isolated fixtures. */
class ReviewProgressTransportIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NGINX = "nginxinc/nginx-unprivileged:1.31.7-alpine-slim@sha256:b94a56c52fd315bbd8f1f4368d64c25983351c978313e02feff2c0b35e856f2d";
    private static final String CADDY = "caddy:2.11.4-alpine@sha256:5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648";
    private static final String OWNER = "progress-" + UUID.randomUUID().toString().replace("-", "");
    private static final AtomicBoolean REVOKED = new AtomicBoolean();
    private static final Map<Integer, String> TOKENS = java.util.stream.IntStream.rangeClosed(1, 9)
        .boxed().collect(java.util.stream.Collectors.toUnmodifiableMap(id -> id, id -> UUID.randomUUID().toString()));
    private static final AtomicInteger PUBLICATION_PROBES = new AtomicInteger();
    private final List<String> containers = new ArrayList<>();
    private final List<String> volumes = new ArrayList<>();
    private final List<Stream> open = new ArrayList<>();
    @TempDir Path temporary;
    private Tomcat tomcat;
    private AnnotationConfigWebApplicationContext context;
    private ReviewProgressStreamService streams;
    private String network;
    private boolean proxyFixture;

    @BeforeEach void startTransport() throws Exception {
        proxyFixture = "true".equals(System.getenv("REPOGUARD_RUN_LINUX_BACKUP_INTEGRATION"));
        if (proxyFixture) {
            assertThat(System.getenv("GITHUB_ACTIONS")).isEqualTo("true");
            assertThat(System.getenv("RUNNER_ENVIRONMENT")).isEqualTo("github-hosted");
            assertThat(System.getenv("GITHUB_RUN_ID")).matches("[1-9][0-9]*");
            assertThat(System.getProperty("os.name")).startsWith("Linux");
        }
        REVOKED.set(false);
        PUBLICATION_PROBES.set(0);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ReviewTask.class);
        TableInfoHelper.initTableInfo(new MapperBuilderAssistant(new MybatisConfiguration(), ""), ReviewTimeline.class);
        tomcat = new Tomcat();
        tomcat.setBaseDir(temporary.resolve("tomcat").toString());
        tomcat.setPort(proxyFixture ? 8081 : 0);
        tomcat.getConnector().setProperty("address", proxyFixture ? "0.0.0.0" : "127.0.0.1");
        tomcat.getConnector().setProperty("maxThreads", "32");
        var servletContext = tomcat.addContext("", temporary.toString());
        context = new AnnotationConfigWebApplicationContext();
        context.register(Fixture.class);
        context.setServletContext(servletContext.getServletContext());
        // Create fixture mocks in the test loader; Mockito caches must not retain a stopped Tomcat loader.
        context.refresh();
        var servlet = Tomcat.addServlet(servletContext, "progress", new DispatcherServlet(context));
        servlet.setLoadOnStartup(1);
        servlet.setAsyncSupported(true);
        servletContext.addServletMapping("/", "progress", false);
        tomcat.start();
        streams = context.getBean(ReviewProgressStreamService.class);
    }

    @Test void realTomcatStreamsWithoutBufferingAndClosesRevokedSession() throws Exception {
        URI endpoint = URI.create("http://127.0.0.1:" + tomcat.getConnector().getLocalPort() + "/api/v1/reviews/9/events");
        HttpClient client = client(null);
        try {
            assertThat(statusCode(client, endpoint, null)).isEqualTo(401);
            verifyRejectedInputs(client, endpoint);
            Stream stream = subscribe(client, endpoint, 1);
            assertThat(stream.response().headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(stream.response().headers().firstValue("X-Accel-Buffering")).contains("no");
            assertProgress(stream);
            Stream second = subscribe(client, endpoint, 1);
            assertProgress(second);
            assertThat(statusCode(client, endpoint, 1)).isEqualTo(429);
            REVOKED.set(true);
            assertThat(readEvent(stream, 8)).isEmpty();
            assertThat(readEvent(second, 8)).isEmpty();
            await(() -> active() == 0, 8);
            assertThat(scheduler().getQueue()).isEmpty();
        } finally { client.shutdownNow(); }
    }

    @Test
    @EnabledOnOs(OS.LINUX)
    @EnabledIfEnvironmentVariable(named = "REPOGUARD_RUN_LINUX_BACKUP_INTEGRATION", matches = "true")
    void realCaddyTlsNginxChainEnforcesLimitsAndReleasesDisconnectedStreams() throws Exception {
        try { verifyProxyTransport(); }
        catch (Exception | AssertionError failure) { reportProxyFailure(); throw failure; }
    }

    private void verifyProxyTransport() throws Exception {
        Path repository = Path.of(System.getenv("GITHUB_WORKSPACE")).toRealPath();
        network = command("docker", "network", "create", "--driver", "bridge", "--opt", "com.docker.network.bridge.host_binding_ipv4=127.0.0.1",
            "--label", "com.repoguard.ci.owner=" + OWNER, "repoguard-ci-" + OWNER);
        assertThat(network).matches("[a-f0-9]{64}");
        String gateway = command("docker", "network", "inspect", "--format", "{{(index .IPAM.Config 0).Gateway}}", network);
        assertThat(gateway).matches("[0-9.]+");
        command("docker", "pull", NGINX);
        command("docker", "pull", CADDY);
        String frontend = container(List.of(NGINX, "-g", "daemon off;"), List.of(
            "--entrypoint", "nginx",
            "--network-alias", "frontend", "--add-host", "backend:" + gateway,
            "--tmpfs", "/tmp:rw,noexec,nosuid,size=16m",
            "--volume", repository.resolve("repoguard-frontend/nginx.conf") + ":/etc/nginx/nginx.conf:ro",
            "--volume", repository.resolve("repoguard-frontend/nginx.ip.conf") + ":/etc/nginx/conf.d/default.conf:ro"));
        Path caddyfile = temporary.resolve("Caddyfile");
        String product = Files.readString(repository.resolve("Caddyfile"));
        String site = "{$REPOGUARD_FRONTEND_SERVER_NAME} {";
        assertThat(product.split(java.util.regex.Pattern.quote(site), -1).length).isEqualTo(2);
        Files.writeString(caddyfile, product.replace(site, site + "\n    tls internal"));
        String dataVolume = command("docker", "volume", "create", "--label", "com.repoguard.ci.owner=" + OWNER, "repoguard-ci-" + OWNER + "-edge-data");
        volumes.add(dataVolume);
        String edge = startProxyEdge(caddyfile, dataVolume);
        assertThat(JSON.readTree(command("docker", "inspect", "--format", "{{json .HostConfig.CapDrop}}", edge)))
            .isEqualTo(JSON.readTree("[\"ALL\"]"));
        assertThat(JSON.readTree(command("docker", "inspect", "--format", "{{json .HostConfig.CapAdd}}", edge)))
            .isEqualTo(JSON.readTree("[\"CAP_NET_BIND_SERVICE\"]"));
        byte[][] ca = new byte[1][];
        await(() -> {
            try {
                ca[0] = command("docker", "exec", edge, "cat", "/data/caddy/pki/authorities/local/root.crt").getBytes(StandardCharsets.US_ASCII);
                return ca[0].length > 0;
            } catch (Exception | AssertionError e) { return false; }
        }, 15);
        String binding = command("docker", "port", edge, "443/tcp");
        assertThat(binding).matches("127\\.0\\.0\\.1:[1-9][0-9]*");
        URI endpoint = URI.create("https://localhost:" + binding.substring(binding.lastIndexOf(':') + 1) + "/api/v1/reviews/9/events");
        try (HttpClient client = client(trust(ca[0]))) {
            URI health = endpoint.resolve("/healthz");
            await(() -> {
                try {
                    return client.send(HttpRequest.newBuilder(health).timeout(Duration.ofSeconds(2)).GET().build(),
                        HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
                } catch (Exception e) { return false; }
            }, 15);
            assertThat(statusCode(client, endpoint, null)).isEqualTo(401);
            verifyRejectedInputs(client, endpoint);
            for (int user = 1; user <= 8; user++) {
                for (int connection = 0; connection < 2; connection++) {
                    Stream stream = subscribe(client, endpoint, user);
                    assertProgress(stream);
                }
                assertThat(statusCode(client, endpoint, user)).isEqualTo(429);
            }
            assertThat(active()).isEqualTo(16);
            assertThat(statusCode(client, endpoint, 9)).isEqualTo(429);
            assertThat(scheduler().getPoolSize()).isLessThanOrEqualTo(2);
            assertThat(scheduler().getQueue().size()).isLessThanOrEqualTo(16);
            for (String container : List.of(frontend, edge)) {
                assertThat(command("docker", "inspect", "--format", "{{.HostConfig.Memory}}", container)).isEqualTo("67108864");
                assertThat(Long.parseLong(command("docker", "exec", container, "cat", "/sys/fs/cgroup/memory.current")))
                    .isPositive().isLessThanOrEqualTo(64L * 1024 * 1024);
            }
            for (Stream stream : open) stream.reader().close();
            open.clear();
            await(() -> active() == 0 && scheduler().getQueue().isEmpty(), 20);
            Stream recovered = subscribe(client, endpoint, 1);
            assertProgress(recovered);
            assertThat(active()).isEqualTo(1);
            recovered.reader().close(); open.clear();
            await(() -> active() == 0 && scheduler().getQueue().isEmpty(), 20);
        }
        verifyPublicationMaintenance(repository, edge, caddyfile, dataVolume, ca[0]);
    }

    private String startProxyEdge(Path caddyfile, String dataVolume) throws Exception {
        return container(List.of(CADDY, "run", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile"), List.of(
            "--entrypoint", "caddy", "--cap-add", "NET_BIND_SERVICE", "--publish", "127.0.0.1::443", "--env", "REPOGUARD_FRONTEND_SERVER_NAME=localhost",
            "--volume", dataVolume + ":/data", "--tmpfs", "/config:rw,noexec,nosuid,size=4m",
            "--volume", caddyfile + ":/etc/caddy/Caddyfile:ro"));
    }

    private URI proxyBase(String edge) throws Exception {
        String binding = command("docker", "port", edge, "443/tcp");
        assertThat(binding).matches("127\\.0\\.0\\.1:[1-9][0-9]*");
        return URI.create("https://localhost:" + binding.substring(binding.lastIndexOf(':') + 1));
    }

    private String recreateProxyEdge(String edge, Path config, String volume) throws Exception {
        command("docker", "rm", "--force", edge); containers.remove(edge);
        return startProxyEdge(config, volume);
    }

    private void awaitProxy(HttpClient client, URI base) throws Exception {
        await(() -> {
            try { return client.send(HttpRequest.newBuilder(base.resolve("/healthz")).timeout(Duration.ofSeconds(2)).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode() == 200; }
            catch (Exception e) { return false; }
        }, 15);
    }

    private void publicationRequest(HttpClient client, URI base, String method, String path, boolean blocked) throws Exception {
        int before = PUBLICATION_PROBES.get();
        var response = client.send(HttpRequest.newBuilder(base.resolve(path)).timeout(Duration.ofSeconds(8))
            .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).as(method + " " + path).isEqualTo(blocked ? 503 : 200);
        assertThat(PUBLICATION_PROBES.get()).as("Blocked requests must not reach even the isolated application").isEqualTo(before + (blocked ? 0 : 1));
        if (blocked) {
            assertThat(response.headers().firstValue("X-RepoGuard-Publication-Maintenance")).contains("true");
            assertThat(response.headers().firstValue("Retry-After")).contains("60");
            assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
            assertThat(JSON.readTree(response.body()).path("success").asBoolean()).isFalse();
            assertThat(JSON.readTree(response.body()).path("code").asText()).isEqualTo("SERVICE_UNAVAILABLE");
        } else assertThat(JSON.readTree(response.body()).path("proxied").asBoolean()).isTrue();
    }

    private int deploymentGuard(Path repository, String edge, Path compose, String expectedVolume, boolean required) throws Exception {
        String script = Files.readString(repository.resolve("scripts/deploy-prod.sh"));
        String functions = script.substring(script.indexOf("verify_publication_maintenance() {"), script.indexOf("restore_deployment_assets() {"));
        String resolver = "compose() { if [ \"$1\" = ps ]; then printf '%s\\n' \"$TEST_EDGE\"; else docker compose -f \"$TEST_COMPOSE\" \"$@\"; fi; }\n";
        var process = new ProcessBuilder("sh", "-ec", resolver + functions + "\nverify_publication_maintenance\nvalidate_publication_maintenance_volume");
        process.environment().putAll(Map.of("PUBLICATION_MAINTENANCE_REQUIRED", Boolean.toString(required),
            "expected_caddy_maintenance_volume", expectedVolume, "TEST_EDGE", edge, "TEST_COMPOSE", compose.toString()));
        Path diagnostic = temporary.resolve("maintenance-guard.log");
        Process running = process.redirectErrorStream(true).redirectOutput(diagnostic.toFile()).start();
        try { assertThat(running.waitFor(30, TimeUnit.SECONDS)).isTrue(); return running.exitValue(); }
        finally { if (running.isAlive()) running.destroyForcibly(); }
    }

    private void restoreAssets(Path repository, Path config, Path backup, boolean required) throws Exception {
        String script = Files.readString(repository.resolve("scripts/deploy-prod.sh"));
        String function = script.substring(script.indexOf("restore_deployment_assets() {"), script.indexOf("recreate_edge_proxy_chain() {"));
        var process = new ProcessBuilder("sh", "-ec", function + "\nrestore_deployment_assets false");
        process.environment().putAll(Map.of("PUBLICATION_MAINTENANCE_REQUIRED", Boolean.toString(required),
            "COMPOSE_FILE", config.getParent().resolve("docker-compose.prod.yml").toString(), "DEPLOY_ASSET_BACKUP_DIR", backup.toString()));
        Process running = process.redirectErrorStream(true).redirectOutput(temporary.resolve("maintenance-restore.log").toFile()).start();
        try { assertThat(running.waitFor(15, TimeUnit.SECONDS)).isTrue(); assertThat(running.exitValue()).isZero(); }
        finally { if (running.isAlive()) running.destroyForcibly(); }
    }

    private void verifyPublicationMaintenance(Path repository, String initialEdge, Path config, String volume, byte[] ca) throws Exception {
        Path compose = temporary.resolve("maintenance-compose.yml");
        String model = "services:\n  caddy:\n    image: " + CADDY + "\n    volumes:\n      - edge_data:/data\nvolumes:\n  edge_data:\n    name: " + volume + "\n";
        Files.writeString(compose, model);
        String gateConfig = Files.readString(config);
        String edge = initialEdge;
        var proof = new java.util.LinkedHashMap<String, Object>();
        try (HttpClient client = client(trust(ca))) {
            URI base = proxyBase(edge); awaitProxy(client, base);
            publicationRequest(client, base, "POST", "/api/v1/reviews/9/github-comments", false);
            assertThat(deploymentGuard(repository, edge, compose, "", false)).isZero();
            assertThat(deploymentGuard(repository, edge, compose, "", true)).as("Missing marker must fail preflight").isNotZero();
            command("docker", "exec", edge, "sh", "-ec", "umask 077; mkdir -p /data/repoguard-maintenance; : > /data/repoguard-maintenance/publication-blocked");
            List<String> paths = List.of("/api/v1/reviews/9/github-comments", "/api/v1/reviews/9/github-comments/",
                "/api/v1/reviews/9/github-comments/resolutions/1/verify", "/api/v1/reviews/9/github-comments/resolutions/1/close",
                "/api/v1/reviews;scope/9/github-comments", "/api/v1/reviews/9/github-comments;scope", "/api/v1/reviews/9/%67ithub-comments?preview=false");
            for (String path : paths) publicationRequest(client, base, "POST", path, true);
            publicationRequest(client, base, "GET", "/api/v1/reviews/9/github-comments", false);
            publicationRequest(client, base, "POST", "/api/v1/reviews/9/retry", false);
            Stream stream = subscribe(client, base.resolve("/api/v1/reviews/9/events"), 1); assertProgress(stream);
            stream.reader().close(); open.clear(); await(() -> active() == 0, 15);
            assertThat(deploymentGuard(repository, edge, compose, "", true)).isZero();
            assertThat(deploymentGuard(repository, edge, compose, volume + "-unexpected", true)).isNotZero();
            Files.writeString(compose, model.replace("name: " + volume, "name: " + volume + "-replacement"));
            assertThat(deploymentGuard(repository, edge, compose, "", true)).isNotZero();
            Files.writeString(compose, model);
            edge = recreateProxyEdge(edge, config, volume); base = proxyBase(edge); awaitProxy(client, base);
            publicationRequest(client, base, "POST", paths.getFirst(), true);
            assertThat(deploymentGuard(repository, edge, compose, volume, true)).isZero();
            Path backup = temporary.resolve("previous-assets"); Files.createDirectories(backup);
            String legacy = "localhost {\n    tls internal\n    respond \"legacy\" 200\n}\n:80 {\n    respond \"legacy\" 200\n}\n";
            Files.writeString(backup.resolve("Caddyfile"), legacy);
            restoreAssets(repository, config, backup, true);
            assertThat(Files.readString(config)).isEqualTo(gateConfig);
            edge = recreateProxyEdge(edge, config, volume); base = proxyBase(edge); awaitProxy(client, base);
            publicationRequest(client, base, "POST", paths.getLast(), true);
            assertThat(deploymentGuard(repository, edge, compose, volume, true)).isZero();
            restoreAssets(repository, config, backup, false);
            assertThat(Files.readString(config)).isEqualTo(legacy);
            edge = recreateProxyEdge(edge, config, volume); base = proxyBase(edge); awaitProxy(client, base);
            assertThat(deploymentGuard(repository, edge, compose, volume, true)).as("A marker alone must not pass with a legacy Caddyfile").isNotZero();
            Files.writeString(config, gateConfig);
            edge = recreateProxyEdge(edge, config, volume); base = proxyBase(edge); awaitProxy(client, base);
            assertThat(deploymentGuard(repository, edge, compose, volume, true)).isZero();
            command("docker", "exec", edge, "rm", "/data/repoguard-maintenance/publication-blocked");
            for (String path : paths.subList(0, 4)) publicationRequest(client, base, "POST", path, false);
            assertThat(deploymentGuard(repository, edge, compose, volume, true)).isNotZero();
            proof.putAll(Map.of("blockedPostVariants", paths.size(), "blockedRequestsReachedBackend", 0, "markerSurvivedRecreation", true,
                "candidateConfigRetainedDuringRollback", true, "legacyConfigRejected", true, "markerAbsentRejected", true,
                "changedRuntimeVolumeRejected", true, "replacementComposeVolumeRejected", true, "defaultOffAndReleaseForwarded", true));
        }
        proof.put("runId", System.getenv("GITHUB_RUN_ID")); proof.put("revision", System.getenv("GITHUB_SHA"));
        proof.put("caddy", CADDY); proof.put("volume", volume);
        proof.put("caddyfileSha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(repository.resolve("Caddyfile")))));
        proof.put("deployScriptSha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(repository.resolve("scripts/deploy-prod.sh")))));
        proof.put("productionWrites", 0); proof.put("realGithubPosts", 0); proof.put("fullApplicationRollback", false); proof.put("passed", true);
        Path evidence = Path.of("target/publication-maintenance"); Files.createDirectories(evidence);
        Files.writeString(evidence.resolve("result.json"), JSON.writerWithDefaultPrettyPrinter().writeValueAsString(proof));
    }

    private void verifyRejectedInputs(HttpClient client, URI endpoint) throws Exception {
        for (String cursor : List.of("-1", "not-a-number", "9223372036854775808")) {
            var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(8))
                .header("Authorization", "Bearer " + TOKENS.get(1))
                .header("Accept", "text/event-stream").header("Last-Event-ID", cursor).GET().build();
            assertSafeError(client, request, 400);
        }
        for (String id : List.of("0", "not-a-number")) {
            assertSafeError(client, request(endpoint.resolve("/api/v1/reviews/" + id + "/events"), 1), 400);
        }
        var incompatible = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(8))
            .header("Authorization", "Bearer " + TOKENS.get(1)).header("Accept", "application/json").GET().build();
        assertSafeError(client, incompatible, 406);
        assertThat(active()).isZero();
        assertThat(scheduler().getQueue()).isEmpty();
    }

    private static void assertSafeError(HttpClient client, HttpRequest request, int expectedStatus) throws Exception {
        var response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertThat(response.statusCode()).isEqualTo(expectedStatus);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/json");
        assertThat(response.headers().firstValue(GlobalExceptionHandler.ERROR_ID_HEADER)).isEmpty();
        var body = JSON.readTree(response.body());
        assertThat(body.path("success").asBoolean()).isFalse();
        assertThat(body.path("code").asText()).isEqualTo("BAD_REQUEST");
        assertThat(body.path("message").asText()).isIn("Request validation failed", "Requested response type is not supported");
        assertThat(response.body()).doesNotContain("not-a-number", "9223372036854775808", "ConstraintViolation", "Exception", "Bearer");
    }

    private void reportProxyFailure() {
        for (String id : containers) {
            try {
                String diagnostic = command("docker", "inspect", "--format", "{{.State.Status}}|{{.State.ExitCode}}|{{.State.OOMKilled}}", id)
                    + "\n" + command("docker", "logs", "--tail", "20", id);
                for (String token : TOKENS.values()) diagnostic = diagnostic.replace(token, "[masked]");
                diagnostic = diagnostic.replaceAll("[a-fA-F0-9]{32,}", "[masked]");
                System.out.println("Isolated proxy fixture: " + diagnostic.substring(0, Math.min(4096, diagnostic.length())));
            } catch (Exception | AssertionError ignored) { System.out.println("Isolated proxy diagnostics unavailable"); }
        }
    }

    private Stream subscribe(HttpClient client, URI endpoint, int user) throws Exception {
        long start = System.nanoTime();
        var response = client.send(request(endpoint, user), HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        Stream stream = new Stream(response, new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8)), start);
        open.add(stream);
        return stream;
    }

    private void assertProgress(Stream stream) throws Exception {
        String event = readEvent(stream, 3);
        assertThat(Duration.ofNanos(System.nanoTime() - stream.startedAt()).toMillis()).isLessThan(3000);
        assertThat(event).contains("event:progress\n", "id:101\n");
        String data = event.lines().filter(line -> line.startsWith("data:")).findFirst().orElseThrow().substring(5);
        assertThat(JSON.readTree(data).path("taskId").asLong()).isEqualTo(9);
    }

    private static String readEvent(Stream stream, int seconds) throws Exception {
        try (var readers = Executors.newVirtualThreadPerTaskExecutor()) {
            var reading = readers.submit(() -> {
                StringBuilder event = new StringBuilder();
                String line;
                while ((line = stream.reader().readLine()) != null) {
                    if (line.isEmpty()) return event.toString();
                    assertThat(event.length() + line.length()).isLessThan(4096);
                    event.append(line).append('\n');
                }
                return event.toString();
            });
            try { return reading.get(seconds, TimeUnit.SECONDS); }
            catch (java.util.concurrent.TimeoutException timeout) {
                stream.response().body().close(); reading.cancel(true); throw timeout;
            }
        }
    }

    private static int statusCode(HttpClient client, URI endpoint, Integer user) throws Exception {
        return client.send(request(endpoint, user), HttpResponse.BodyHandlers.discarding()).statusCode();
    }

    private static HttpRequest request(URI endpoint, Integer user) {
        var request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(8)).header("Accept", "text/event-stream");
        if (user != null) request.header("Authorization", "Bearer " + TOKENS.get(user));
        return request.GET().build();
    }

    private static HttpClient client(SSLContext trust) {
        var builder = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(3));
        if (trust != null) builder.sslContext(trust);
        return builder.build();
    }

    private static SSLContext trust(byte[] certificate) throws Exception {
        var ca = CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(certificate));
        KeyStore keys = KeyStore.getInstance(KeyStore.getDefaultType()); keys.load(null, null);
        keys.setCertificateEntry("fixture-ca", ca);
        var managers = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()); managers.init(keys);
        SSLContext trust = SSLContext.getInstance("TLS"); trust.init(null, managers.getTrustManagers(), null);
        return trust;
    }

    @SuppressWarnings("unchecked")
    private int active() { return ((Map<String, ?>) ReflectionTestUtils.getField(streams, "connections")).size(); }
    private ScheduledThreadPoolExecutor scheduler() { return (ScheduledThreadPoolExecutor) ReflectionTestUtils.getField(streams, "scheduler"); }

    private String container(List<String> imageAndCommand, List<String> options) throws Exception {
        var args = new ArrayList<>(List.of("docker", "run", "--detach", "--network", network,
            "--read-only", "--cap-drop", "ALL", "--security-opt", "no-new-privileges:true",
            "--memory", "64m", "--memory-swap", "64m", "--cpus", "0.50", "--pids-limit", "64",
            "--restart", "no", "--label", "com.repoguard.ci.owner=" + OWNER));
        args.addAll(options); args.addAll(imageAndCommand);
        String id = command(args.toArray(String[]::new));
        assertThat(id).matches("[a-f0-9]{64}"); containers.add(id);
        return id;
    }

    private static String command(String... args) throws Exception {
        Process process = new ProcessBuilder(args).redirectErrorStream(true).start();
        try (var readers = Executors.newVirtualThreadPerTaskExecutor()) {
            var output = readers.submit(() -> process.getInputStream().readNBytes(32769));
            if (!process.waitFor(120, TimeUnit.SECONDS)) {
                process.destroyForcibly(); throw new AssertionError("Isolated proxy command deadline");
            }
            byte[] bytes = output.get(5, TimeUnit.SECONDS);
            assertThat(bytes.length).isLessThanOrEqualTo(32768);
            assertThat(process.exitValue()).as("Isolated proxy command exit").isZero();
            return new String(bytes, StandardCharsets.UTF_8).strip();
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    private static void await(BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) throw new AssertionError("Live progress transport deadline");
            Thread.sleep(100);
        }
    }

    @AfterEach void cleanup() throws Exception {
        try {
            for (Stream stream : open) stream.reader().close();
        } finally {
            try {
                for (String id : containers.reversed()) {
                    assertThat(command("docker", "inspect", "--format", "{{.Id}}|{{index .Config.Labels \"com.repoguard.ci.owner\"}}", id)).isEqualTo(id + "|" + OWNER);
                    command("docker", "rm", "--force", id);
                }
                for (String volume : volumes.reversed()) {
                    assertThat(command("docker", "volume", "inspect", "--format", "{{index .Labels \"com.repoguard.ci.owner\"}}", volume)).isEqualTo(OWNER);
                    command("docker", "volume", "rm", volume);
                }
                if (network != null) {
                    assertThat(command("docker", "network", "inspect", "--format", "{{.Id}}|{{index .Labels \"com.repoguard.ci.owner\"}}", network)).isEqualTo(network + "|" + OWNER);
                    command("docker", "network", "rm", network);
                }
            } finally {
                if (tomcat != null) { tomcat.stop(); tomcat.destroy(); }
                if (context != null) context.close();
            }
        }
    }

    private record Stream(HttpResponse<InputStream> response, BufferedReader reader, long startedAt) {}

    @org.springframework.web.bind.annotation.RestController
    static class PublicationProbeController {
        @org.springframework.web.bind.annotation.RequestMapping({"/api/v1/reviews/{taskId}/github-comments", "/api/v1/reviews/{taskId}/github-comments/**", "/api/v1/reviews/{taskId}/retry"})
        Map<String, Boolean> probe() { PUBLICATION_PROBES.incrementAndGet(); return Map.of("proxied", true); }
    }

    @TestConfiguration @EnableWebMvc
    static class Fixture implements WebMvcConfigurer {
        @Bean static MethodValidationPostProcessor methodValidation() {
            var validation = new MethodValidationPostProcessor();
            validation.setProxyTargetClass(true);
            return validation;
        }
        @Bean ReviewProgressStreamService streams() {
            ReviewTaskMapper tasks = mock(ReviewTaskMapper.class);
            ReviewTimelineMapper timelines = mock(ReviewTimelineMapper.class);
            AuthAccountCache accounts = mock(AuthAccountCache.class);
            ReviewTask task = new ReviewTask(); task.setId(9L); task.setStatus("QUEUED");
            ReviewTimeline timeline = new ReviewTimeline(); timeline.setId(101L);
            when(tasks.selectOne(any())).thenReturn(task);
            when(timelines.selectOne(any())).thenReturn(timeline);
            when(accounts.findById(any())).thenAnswer(_ -> {
                UserAccount account = new UserAccount();
                account.setStatus(REVOKED.get() ? "DISABLED" : "ACTIVE"); account.setSessionVersion(2);
                return account;
            });
            return new ReviewProgressStreamService(tasks, timelines, accounts, new TenantProperties(), mock(TenantResolutionService.class));
        }
        @Bean ReviewProgressController controller(ReviewProgressStreamService streams) { return new ReviewProgressController(streams); }
        @Bean GlobalExceptionHandler errors() { return new GlobalExceptionHandler(); }
        @Bean PublicationProbeController publicationProbe() { return new PublicationProbeController(); }
        @Override public void addInterceptors(InterceptorRegistry registry) {
            registry.addInterceptor(new HandlerInterceptor() {
                @Override public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                    TOKENS.forEach((user, token) -> {
                        if (("Bearer " + token).equals(request.getHeader("Authorization"))) {
                            request.setAttribute(RequestAuthenticationAttributes.AUTHENTICATED_PRINCIPAL,
                                new AuthenticatedPrincipal(user.longValue(), "fixture", "USER", Instant.now().plusSeconds(300).getEpochSecond(), 2));
                        }
                    });
                    return true;
                }
            });
        }
    }
}
