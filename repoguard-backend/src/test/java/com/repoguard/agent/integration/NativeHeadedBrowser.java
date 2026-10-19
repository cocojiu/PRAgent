package com.repoguard.agent.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

/** Own plain Chromium profile, attached with noDefaults so Playwright never enables focused-page emulation. */
final class NativeHeadedBrowser implements AutoCloseable {
    final Browser browser;
    private Process process;
    private final java.net.ServerSocket denyProxy;
    private final java.util.concurrent.atomic.AtomicInteger blockedConnections = new java.util.concurrent.atomic.AtomicInteger();
    private final Path log;
    private final Path evidence;
    NativeHeadedBrowser(Playwright playwright, Path temporary, Path evidence, String origin) throws Exception {
        this.evidence = evidence; log = temporary.resolve("native-chromium.log");
        Path profile = temporary.resolve("native-chromium-profile"); Files.createDirectory(profile);
        String edgeHost = java.net.URI.create(origin).getHost();
        assertThat(edgeHost).matches("[0-9.]+");
        denyProxy = new java.net.ServerSocket(0, 8, java.net.InetAddress.getByName("127.0.0.1"));
        Thread.ofVirtual().name("isolated-browser-deny-proxy").start(() -> {
            while (!denyProxy.isClosed()) {
                try (var connection = denyProxy.accept()) { blockedConnections.incrementAndGet(); connection.shutdownOutput(); }
                catch (java.io.IOException e) { if (denyProxy.isClosed()) return; }
            }
        });
        try {
        process = new ProcessBuilder(playwright.chromium().executablePath(), "--remote-debugging-address=127.0.0.1", "--remote-debugging-port=0",
            "--user-data-dir=" + profile, "--no-sandbox", "--no-first-run", "--no-default-browser-check", "--ignore-certificate-errors", "--disable-dev-shm-usage",
            "--disable-background-networking", "--disable-component-update", "--disable-sync", "--dns-prefetch-disable",
            "--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE localhost",
            "--proxy-server=http://127.0.0.1:" + denyProxy.getLocalPort(), "--proxy-bypass-list=" + edgeHost, "about:blank")
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
            IsolatedReviewRuntime.await(() -> Files.exists(profile.resolve("DevToolsActivePort")) && process.isAlive(), 15);
            var lines = Files.readAllLines(profile.resolve("DevToolsActivePort"));
            assertThat(lines.getFirst()).matches("[1-9][0-9]{0,4}");
            int port = Integer.parseInt(lines.getFirst()); assertThat(port).isBetween(1, 65535);
            browser = playwright.chromium().connectOverCDP("http://127.0.0.1:" + port, new BrowserType.ConnectOverCDPOptions().setNoDefaults(true));
            assertThat(browser.contexts()).hasSize(1);
        } catch (Exception | AssertionError e) { stop(); throw e; }
    }
    private void stop() throws InterruptedException {
        try { denyProxy.close(); } catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        if (process == null) return;
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); assertThat(process.waitFor(5, TimeUnit.SECONDS)).isTrue(); }
    }
    @Override public void close() {
        try { browser.close(); }
        finally {
            try {
                stop();
                String diagnostic;
                try (var input = Files.newInputStream(log)) { diagnostic = new String(input.readNBytes(32768), java.nio.charset.StandardCharsets.UTF_8); }
                diagnostic = diagnostic.replaceAll("ws://127\\.0\\.0\\.1:[0-9]+/[^\\s]+", "[closed isolated browser endpoint]");
                Files.writeString(evidence.resolve("native-chromium.log"), diagnostic);
                Files.writeString(evidence.resolve("native-browser-isolation.json"), IsolatedReviewRuntime.JSON.writeValueAsString(java.util.Map.of(
                    "proxyLoopbackOnly", true, "proxyConnectionsRejected", blockedConnections.get(), "externalRequestsForwarded", 0,
                    "resolverBlocksExternalNames", true, "ownedProcessStopped", !process.isAlive())));
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        }
    }
}
