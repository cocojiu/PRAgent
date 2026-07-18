package com.repoguard.agent.quality;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class RepositoryGovernanceGuardTest {

    private static final Set<String> ALLOWED_MARKDOWN = Set.of("README.md");
    private static final List<String> SCRIPT_EXTENSIONS = List.of(".sh", ".ps1", ".bat", ".cmd");

    @Test
    void trackedFilesRespectRepositoryGovernanceRules() throws Exception {
        Path repositoryRoot = findRepositoryRoot();
        List<String> trackedFiles = listTrackedFiles(repositoryRoot);

        List<String> invalidMarkdown = new ArrayList<>();
        List<String> invalidScripts = new ArrayList<>();
        for (String trackedFile : trackedFiles) {
            if (isInvalidMarkdown(trackedFile)) {
                invalidMarkdown.add(trackedFile);
            }
            if (isTestOrTemporaryScript(trackedFile)) {
                invalidScripts.add(trackedFile);
            }
        }

        assertEquals(List.of(), invalidMarkdown, "Only approved root markdown files may be tracked");
        assertEquals(List.of(), invalidScripts, "Test or temporary scripts must not be tracked");
    }

    @Test
    void governanceClassifierAllowsApprovedRootMarkdownOnly() {
        assertFalse(isInvalidMarkdown("README.md"));
        assertTrue(isInvalidMarkdown("代码优化审查报告.md"));
        assertTrue(isInvalidMarkdown("docs/release/checklist.md"));
        assertTrue(isInvalidMarkdown("repoguard-frontend/README.md"));
        assertTrue(isInvalidMarkdown("README.zh.md"));
    }

    @Test
    void governanceClassifierDistinguishesTestScriptsFromAllowedSources() {
        assertTrue(isTestOrTemporaryScript("scripts/test/run-smoke.ps1"));
        assertTrue(isTestOrTemporaryScript("scripts/release-test.sh"));
        assertTrue(isTestOrTemporaryScript("tools/debug.cmd"));
        assertTrue(isTestOrTemporaryScript("tmp/bootstrap.bat"));
        assertTrue(isTestOrTemporaryScript("repoguard-backend/src/test/resources/local-helper.sh"));

        assertFalse(isTestOrTemporaryScript("scripts/production-readiness-check.ps1"));
        assertFalse(isTestOrTemporaryScript("scripts/deploy-prod.sh"));
        assertFalse(isTestOrTemporaryScript("scripts/bootstrap-docker-server.sh"));
        assertFalse(isTestOrTemporaryScript("repoguard-backend/src/test/java/com/repoguard/agent/FooTest.java"));
        assertFalse(isTestOrTemporaryScript("repoguard-backend/src/test/resources/review-quality/evaluation-cases.json"));
    }

    private static Path findRepositoryRoot() throws IOException {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve(".git")) && Files.isDirectory(current.resolve("repoguard-backend"))) {
                return current;
            }
            current = current.getParent();
        }
        fail("Cannot locate repository root from " + Path.of("").toAbsolutePath());
        throw new IllegalStateException("unreachable");
    }

    private static List<String> listTrackedFiles(Path repositoryRoot) throws Exception {
        String safeDirectory = repositoryRoot.toString().replace('\\', '/');
        Process process = new ProcessBuilder(
            "git",
            "-c",
            "safe.directory=" + safeDirectory,
            "-C",
            repositoryRoot.toString(),
            "ls-files"
        )
            .redirectErrorStream(false)
            .start();
        ExecutorService streamReader = Executors.newFixedThreadPool(2);
        try {
            Future<String> stdoutFuture = streamReader.submit(() ->
                new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8)
            );
            Future<String> stderrFuture = streamReader.submit(() ->
                new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8)
            );

            boolean completed = process.waitFor(Duration.ofSeconds(10).toMillis(), TimeUnit.MILLISECONDS);
            if (!completed) {
                process.destroyForcibly();
                fail("git ls-files timed out");
            }
            String stdout = stdoutFuture.get(2, TimeUnit.SECONDS);
            String stderr = stderrFuture.get(2, TimeUnit.SECONDS);
            if (process.exitValue() != 0) {
                fail("git ls-files failed: " + stderr);
            }
            return stdout.lines()
                .map(path -> path.replace('\\', '/'))
                .filter(path -> !path.isBlank())
                .toList();
        } finally {
            streamReader.shutdownNow();
        }
    }

    private static boolean isInvalidMarkdown(String path) {
        return path.toLowerCase(Locale.ROOT).endsWith(".md") && !ALLOWED_MARKDOWN.contains(path);
    }

    private static boolean isTestOrTemporaryScript(String path) {
        String normalizedPath = path.replace('\\', '/').toLowerCase(Locale.ROOT);
        String extension = extension(normalizedPath);
        if (!SCRIPT_EXTENSIONS.contains(extension)) {
            return false;
        }

        List<String> segments = List.of(normalizedPath.split("/"));
        for (String segment : segments) {
            if (List.of("test", "tests", "__tests__", "tmp", "temp", "scratch").contains(segment)) {
                return true;
            }
        }

        String fileName = normalizedPath.substring(normalizedPath.lastIndexOf('/') + 1);
        String baseName = fileName.substring(0, fileName.length() - extension.length());
        for (String token : baseName.split("[-_.]")) {
            if (List.of("test", "spec", "tmp", "temp", "scratch", "debug", "local").contains(token)) {
                return true;
            }
        }
        return false;
    }

    private static String extension(String path) {
        int slash = path.lastIndexOf('/');
        int dot = path.lastIndexOf('.');
        if (dot <= slash) {
            return "";
        }
        return path.substring(dot);
    }
}
