package com.repoguard.agent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/** Bounded observer of real proxy SSE responses; never substitutes the controller or authentication. */
final class IsolatedSseSubscriber implements AutoCloseable {
    private final InputStream input;
    private final CompletableFuture<Void> ended = new CompletableFuture<>();
    private final AtomicInteger progress = new AtomicInteger();
    private final AtomicInteger keepalive = new AtomicInteger();
    private volatile boolean closing;

    IsolatedSseSubscriber(IsolatedReviewRuntime runtime, long task, String token, String cursor) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(runtime.baseUrl + "/api/v1/reviews/" + task + "/events"))
            .timeout(Duration.ofSeconds(120)).header("Authorization", "Bearer " + token).header("Accept", "text/event-stream")
            .header("Last-Event-ID", cursor).GET().build();
        var response = runtime.http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        assertThat(response.headers().firstValue("X-Accel-Buffering").orElse("")).isEqualTo("no");
        input = response.body();
        Thread.ofVirtual().name("isolated-sse-observer").start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                int bytes = 0; String line;
                while ((line = reader.readLine()) != null) {
                    bytes += line.length(); assertThat(bytes).isLessThan(32768);
                    if (line.startsWith(":")) keepalive.incrementAndGet();
                    if (line.startsWith("data:")) {
                        assertThat(IsolatedReviewRuntime.JSON.readTree(line.substring(5)).path("taskId").asLong()).isEqualTo(task);
                        progress.incrementAndGet();
                    }
                }
                ended.complete(null);
            } catch (Exception | AssertionError e) {
                if (closing) ended.complete(null); else ended.completeExceptionally(e);
            }
        });
    }
    void awaitProgress() throws Exception { IsolatedReviewRuntime.await(() -> progress.get() > 0 || ended.isDone(), 8); assertThat(progress.get()).isPositive(); }
    void awaitRevoked() throws Exception { ended.get(12, TimeUnit.SECONDS); assertThat(closing).isFalse(); }
    Map<String, Object> summary() { return Map.of("progressFrames", progress.get(), "keepaliveFrames", keepalive.get(), "serverEnded", ended.isDone()); }
    @Override public void close() { closing = true; try { input.close(); } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); } }
}
