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
    private final Process process;
    private final Path log;
    private final Path evidence;
    NativeHeadedBrowser(Playwright playwright, Path temporary, Path evidence) throws Exception {
        this.evidence = evidence; log = temporary.resolve("native-chromium.log");
        Path profile = temporary.resolve("native-chromium-profile"); Files.createDirectory(profile);
        process = new ProcessBuilder(playwright.chromium().executablePath(), "--remote-debugging-address=127.0.0.1", "--remote-debugging-port=0",
            "--user-data-dir=" + profile, "--no-sandbox", "--no-first-run", "--no-default-browser-check", "--ignore-certificate-errors", "about:blank")
            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            IsolatedReviewRuntime.await(() -> Files.exists(profile.resolve("DevToolsActivePort")) && process.isAlive(), 15);
            var lines = Files.readAllLines(profile.resolve("DevToolsActivePort"));
            assertThat(lines.getFirst()).matches("[1-9][0-9]{0,4}");
            int port = Integer.parseInt(lines.getFirst()); assertThat(port).isBetween(1, 65535);
            browser = playwright.chromium().connectOverCDP("http://127.0.0.1:" + port, new BrowserType.ConnectOverCDPOptions().setNoDefaults(true));
            assertThat(browser.contexts()).hasSize(1);
        } catch (Exception | AssertionError e) { stop(); throw e; }
    }
    private void stop() throws InterruptedException {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        if (!process.waitFor(5, TimeUnit.SECONDS)) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); }
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
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            catch (java.io.IOException e) { throw new java.io.UncheckedIOException(e); }
        }
    }
}
