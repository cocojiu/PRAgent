package com.repoguard.agent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.KeyStore;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;

/** HTTPS-only supplier boundary; records bounded request metadata and never forwards requests. */
final class SupplierStub implements AutoCloseable {
    static final String COMMIT = "a".repeat(40);
    final List<Map<String, Object>> requests = new CopyOnWriteArrayList<>();
    final AtomicInteger llmCalls = new AtomicInteger();
    final CountDownLatch llmEntered = new CountDownLatch(1);
    final CountDownLatch releaseLlm = new CountDownLatch(1);
    private final List<Map<String, Object>> publishedComments = new CopyOnWriteArrayList<>();
    private final HttpsServer server;
    private final java.util.concurrent.ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

    static void createCertificates(Path directory, String password) throws Exception {
        Path keytool = Path.of(System.getProperty("java.home"), "bin", "keytool");
        keytool(password, List.of(keytool.toString(), "-genkeypair", "-alias", "supplier", "-keyalg", "RSA", "-keysize", "2048",
            "-validity", "2", "-dname", "CN=supplier.test", "-ext", "SAN=dns:supplier.test", "-storetype", "PKCS12",
            "-keystore", directory.resolve("supplier.p12").toString(), "-storepass:env", "CI_STORE_PASSWORD"));
        Files.setPosixFilePermissions(directory.resolve("supplier.p12"), PosixFilePermissions.fromString("rw-------"));
        keytool(password, List.of(keytool.toString(), "-exportcert", "-alias", "supplier", "-keystore", directory.resolve("supplier.p12").toString(),
            "-storepass:env", "CI_STORE_PASSWORD", "-rfc", "-file", directory.resolve("supplier.crt").toString()));
        keytool(password, List.of(keytool.toString(), "-importcert", "-noprompt", "-alias", "supplier", "-file", directory.resolve("supplier.crt").toString(),
            "-keystore", directory.resolve("trust.p12").toString(), "-storetype", "PKCS12", "-storepass:env", "CI_STORE_PASSWORD"));
        // This store contains only a public certificate; app containers must not receive the server private key.
        Files.setPosixFilePermissions(directory.resolve("trust.p12"), PosixFilePermissions.fromString("rw-r--r--"));
    }
    private static void keytool(String password, List<String> command) throws Exception {
        var builder = new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("CI_STORE_PASSWORD", password);
        var process = builder.start();
        try {
            process.getInputStream().readNBytes(16384);
            assertThat(process.waitFor(30, TimeUnit.SECONDS)).isTrue(); assertThat(process.exitValue()).isZero();
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    SupplierStub(Path directory, String password, String gateway) throws Exception {
        var keys = KeyStore.getInstance("PKCS12");
        try (var input = Files.newInputStream(directory.resolve("supplier.p12"))) { keys.load(input, password.toCharArray()); }
        var managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm()); managers.init(keys, password.toCharArray());
        var tls = SSLContext.getInstance("TLS"); tls.init(managers.getKeyManagers(), null, null);
        server = HttpsServer.create(new InetSocketAddress(gateway, 18443), 32);
        server.setHttpsConfigurator(new HttpsConfigurator(tls)); server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try (exchange) {
                String path = exchange.getRequestURI().getPath(); String method = exchange.getRequestMethod();
                byte[] body = exchange.getRequestBody().readNBytes(262145);
                if (body.length > 262144 || requests.size() >= 256) { exchange.sendResponseHeaders(413, -1); return; }
                requests.add(Map.of("method", method, "path", path, "at", java.time.Instant.now().toString(), "bodyBytes", body.length));
                Object result;
                if (method.equals("POST") && path.equals("/v1/chat/completions")) {
                    llmCalls.incrementAndGet(); llmEntered.countDown();
                    if (!releaseLlm.await(110, TimeUnit.SECONDS)) { exchange.sendResponseHeaders(504, -1); return; }
                    String content = IsolatedReviewRuntime.JSON.writeValueAsString(Map.of("riskLevel", "LOW", "findings", List.of(
                        Map.of("severity", "LOW", "filePath", "src/Example.java", "lineNumber", 2, "message", "Isolated finding", "recommendation", "Add validation"))));
                    result = Map.of("choices", List.of(Map.of("finish_reason", "stop", "message", Map.of("content", content))),
                        "usage", Map.of("prompt_tokens", 100, "completion_tokens", 25, "total_tokens", 125));
                } else if (method.equals("POST") && path.matches("/repos/fixture/sample/pulls/[0-9]+/reviews")) {
                    var review = IsolatedReviewRuntime.JSON.readTree(body);
                    assertThat(review.path("commit_id").asText()).isEqualTo(COMMIT);
                    assertThat(review.path("event").asText()).isEqualTo("COMMENT");
                    for (var comment : review.path("comments")) publishedComments.add(Map.of("id", 1000 + publishedComments.size(),
                        "path", comment.path("path").asText(), "line", comment.path("line").asInt(), "body", comment.path("body").asText(),
                        "html_url", "https://supplier.test:18443/fixtures/review/1"));
                    result = Map.of("id", 1, "html_url", "https://supplier.test:18443/fixtures/review/1");
                } else if (method.equals("GET") && path.matches("/repos/fixture/sample/pulls/[0-9]+/reviews/1/comments")) {
                    result = List.copyOf(publishedComments);
                } else if (method.equals("POST") && path.matches("/repos/fixture/sample/issues/[0-9]+/comments")) {
                    result = Map.of("id", 2001, "html_url", "https://supplier.test:18443/fixtures/summary/2001");
                } else if (method.equals("GET") && path.matches("/repos/fixture/sample/pulls/[0-9]+/files")) {
                    result = List.of(Map.of("filename", "src/Example.java", "status", "modified", "additions", 1, "deletions", 1, "changes", 2,
                        "patch", "@@ -1,3 +1,3 @@\n class Example {\n- int value = 0;\n+ int value = 1;\n }"));
                } else if (method.equals("GET") && path.matches("/repos/fixture/sample/pulls/[0-9]+")) {
                    result = Map.of("number", 1, "updated_at", "2026-10-19T00:00:00Z", "title", "Isolated acceptance", "state", "open", "head", Map.of("sha", COMMIT, "ref", "acceptance"),
                        "base", Map.of("sha", "b".repeat(40), "ref", "main"));
                } else if (method.equals("GET") && path.equals("/repos/fixture/sample")) {
                    result = Map.of("default_branch", "main", "full_name", "fixture/sample");
                } else if (method.equals("GET") && path.equals("/repos/fixture/sample/contents/src/Example.java")) {
                    String content = "class Example {\n int value = " + (java.util.Objects.toString(exchange.getRequestURI().getRawQuery(), "").contains(COMMIT) ? "1" : "0") + ";\n}\n";
                    result = Map.of("type", "file", "encoding", "base64", "content", java.util.Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
                } else if (method.equals("GET") && (path.contains("/contents") || path.contains("/git/trees/"))) {
                    result = path.contains("/git/trees/") ? Map.of("tree", List.of(), "truncated", false) : List.of();
                } else {
                    exchange.sendResponseHeaders(404, -1); return;
                }
                byte[] response = IsolatedReviewRuntime.JSON.writeValueAsBytes(result);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
            } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        });
        server.start();
    }
    @Override public void close() { releaseLlm.countDown(); server.stop(0); executor.shutdownNow(); }
}
