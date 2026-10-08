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
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Real transport and product proxy configuration; authentication and repositories are isolated fixtures. */
class ReviewProgressTransportIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String NGINX = "nginxinc/nginx-unprivileged:1.31.6-alpine-slim@sha256:123fb7283ffb4788e260d4e980005a978995fefedcdbb04d268077a19b84576d";
    private static final String CADDY = "caddy:2.11.4-alpine@sha256:5f5c8640aae01df9654968d946d8f1a56c497f1dd5c5cda4cf95ab7c14d58648";
    private static final String OWNER = "progress-" + UUID.randomUUID().toString().replace("-", "");
    private static final AtomicBoolean REVOKED = new AtomicBoolean();
    private static final Map<Integer, String> TOKENS = java.util.stream.IntStream.rangeClosed(1, 9)
        .boxed().collect(java.util.stream.Collectors.toUnmodifiableMap(id -> id, id -> UUID.randomUUID().toString()));
    private final List<String> containers = new ArrayList<>();
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
        var servlet = Tomcat.addServlet(servletContext, "progress", new DispatcherServlet(context));
        servlet.setLoadOnStartup(1);
        servlet.setAsyncSupported(true);
        servletContext.addServletMapping("/", "progress", false);
        tomcat.start();
        streams = context.getBean(ReviewProgressStreamService.class);
    }

    @Test void realTomcatStreamsWithoutBufferingAndClosesRevokedSession() throws Exception {
        URI endpoint = URI.create("http://127.0.0.1:" + tomcat.getConnector().getLocalPort() + "/api/v1/reviews/9/events");
        try (HttpClient client = client(null)) {
            assertThat(statusCode(client, endpoint, null)).isEqualTo(401);
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
        }
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
        network = command("docker", "network", "create", "--internal", "--label", "com.repoguard.ci.owner=" + OWNER, "repoguard-ci-" + OWNER);
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
        String edge = container(List.of(CADDY, "run", "--config", "/etc/caddy/Caddyfile", "--adapter", "caddyfile"), List.of(
            "--entrypoint", "caddy", "--cap-add", "NET_BIND_SERVICE", "--publish", "127.0.0.1::443", "--env", "REPOGUARD_FRONTEND_SERVER_NAME=localhost",
            "--tmpfs", "/data:rw,noexec,nosuid,size=16m", "--tmpfs", "/config:rw,noexec,nosuid,size=4m",
            "--volume", caddyfile + ":/etc/caddy/Caddyfile:ro"));
        assertThat(JSON.readTree(command("docker", "inspect", "--format", "{{json .HostConfig.CapDrop}}", edge)))
            .isEqualTo(JSON.readTree("[\"ALL\"]"));
        assertThat(JSON.readTree(command("docker", "inspect", "--format", "{{json .HostConfig.CapAdd}}", edge)))
            .isEqualTo(JSON.readTree("[\"NET_BIND_SERVICE\"]"));
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
            assertThat(statusCode(client, endpoint, null)).isEqualTo(401);
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

    @TestConfiguration @EnableWebMvc
    static class Fixture implements WebMvcConfigurer {
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
