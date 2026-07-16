package com.repoguard.agent.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class SupplyChainPolicyTest {

    private static final Pattern ACTION_REFERENCE = Pattern.compile("(?m)^\\s*-?\\s*uses:\\s*([^\\s#]+)");
    private static final Pattern COMMIT_SHA = Pattern.compile("[0-9a-f]{40}");
    private static final Pattern VERSIONED_ACTION_REFERENCE = Pattern.compile(
        "(?m)^\\s*-?\\s*uses:\\s*([^@\\s#]+)@[0-9a-f]{40}\\s+#\\s+v(\\d+)(?:\\.\\d+){0,2}\\s*$"
    );
    private static final Pattern EXCEPTION_ID = Pattern.compile("^\\s*-\\s+id:\\s*(\\S+)\\s*$");
    private static final Pattern EXCEPTION_STATEMENT = Pattern.compile("^\\s+statement:\\s*(.+?)\\s*$");
    private static final Pattern EXCEPTION_EXPIRY = Pattern.compile("^\\s+expired_at:\\s*(\\d{4}-\\d{2}-\\d{2})\\s*$");
    private static final Map<String, Integer> NODE_24_ACTION_MAJOR_BASELINES = Map.ofEntries(
        Map.entry("actions/checkout", 7),
        Map.entry("actions/dependency-review-action", 5),
        Map.entry("actions/github-script", 9),
        Map.entry("actions/setup-java", 5),
        Map.entry("actions/setup-node", 7),
        Map.entry("actions/upload-artifact", 7),
        Map.entry("docker/build-push-action", 7),
        Map.entry("docker/login-action", 4),
        Map.entry("docker/setup-buildx-action", 4)
    );

    @Test
    void externalActionsArePinnedToCommitShas() throws IOException {
        Path workflowDirectory = repositoryRoot().resolve(".github/workflows");
        List<String> violations = new ArrayList<>();
        try (Stream<Path> paths = Files.list(workflowDirectory)) {
            for (Path workflow : paths.filter(Files::isRegularFile).sorted().toList()) {
                String content = Files.readString(workflow, StandardCharsets.UTF_8);
                Matcher matcher = ACTION_REFERENCE.matcher(content);
                while (matcher.find()) {
                    String reference = matcher.group(1);
                    if (reference.startsWith("./")) {
                        continue;
                    }
                    int separator = reference.lastIndexOf('@');
                    String revision = separator < 0 ? "" : reference.substring(separator + 1);
                    if (!COMMIT_SHA.matcher(revision).matches()) {
                        violations.add(workflow.getFileName() + " -> " + reference);
                    }
                }
            }
        }

        assertThat(violations)
            .as("Every external GitHub Action must use an immutable 40-character commit SHA")
            .isEmpty();
    }

    @Test
    void verifiedNode24ActionBaselinesDoNotRegress() throws IOException {
        Path workflowDirectory = repositoryRoot().resolve(".github/workflows");
        Map<String, Integer> observedMajors = new java.util.HashMap<>();
        try (Stream<Path> paths = Files.list(workflowDirectory)) {
            for (Path workflow : paths.filter(Files::isRegularFile).sorted().toList()) {
                Matcher matcher = VERSIONED_ACTION_REFERENCE.matcher(
                    Files.readString(workflow, StandardCharsets.UTF_8)
                );
                while (matcher.find()) {
                    observedMajors.merge(matcher.group(1), Integer.parseInt(matcher.group(2)), Math::max);
                }
            }
        }

        assertThat(observedMajors)
            .as("Every gh-verified Node 24 Action must remain at or above its reviewed major version")
            .containsKeys(NODE_24_ACTION_MAJOR_BASELINES.keySet().toArray(String[]::new));
        NODE_24_ACTION_MAJOR_BASELINES.forEach((action, minimumMajor) ->
            assertThat(observedMajors.get(action))
                .as(action + " major version")
                .isGreaterThanOrEqualTo(minimumMajor)
        );
    }

    @Test
    void dependabotCoversActionsApplicationsAndContainerBases() throws IOException {
        String dependabot = read(".github/dependabot.yml");

        assertThat(dependabot).contains(
            "package-ecosystem: github-actions",
            "package-ecosystem: maven",
            "directory: /repoguard-backend",
            "package-ecosystem: npm",
            "directory: /repoguard-frontend"
        );
        assertThat(count(dependabot, "package-ecosystem: docker")).isEqualTo(2);
        assertThat(count(dependabot, "interval: weekly")).isEqualTo(5);
        assertThat(count(dependabot, "patterns:")).isEqualTo(5);
    }

    @Test
    void pullRequestsScanBuiltBackendLibrariesBeforeRelease() throws IOException {
        String workflow = read(".github/workflows/pr-quality.yml");

        assertThat(workflow).contains(
            "Scan backend libraries for high and critical CVEs",
            "scan-type: fs",
            "scan-ref: repoguard-backend/target",
            "trivyignores: .trivyignore.yaml",
            "vuln-type: library",
            "severity: HIGH,CRITICAL"
        );
    }

    @Test
    void vulnerabilityExceptionsRequireReasonAndUnexpiredDate() throws IOException {
        List<VulnerabilityException> exceptions = parseExceptions(read(".trivyignore.yaml"));
        LocalDate today = LocalDate.now(ZoneOffset.UTC);

        assertThat(exceptions).allSatisfy(exception -> {
            assertThat(exception.statement())
                .as("statement for " + exception.id())
                .isNotBlank();
            assertThat(exception.expiredAt())
                .as("expiry for " + exception.id())
                .isNotNull()
                .isAfterOrEqualTo(today);
        });
    }

    private static List<VulnerabilityException> parseExceptions(String content) {
        List<VulnerabilityException> exceptions = new ArrayList<>();
        String id = null;
        String statement = null;
        LocalDate expiredAt = null;
        for (String line : content.lines().toList()) {
            Matcher idMatcher = EXCEPTION_ID.matcher(line);
            if (idMatcher.matches()) {
                if (id != null) {
                    exceptions.add(new VulnerabilityException(id, statement, expiredAt));
                }
                id = idMatcher.group(1);
                statement = null;
                expiredAt = null;
                continue;
            }
            Matcher statementMatcher = EXCEPTION_STATEMENT.matcher(line);
            if (id != null && statementMatcher.matches()) {
                statement = statementMatcher.group(1);
            }
            Matcher expiryMatcher = EXCEPTION_EXPIRY.matcher(line);
            if (id != null && expiryMatcher.matches()) {
                expiredAt = LocalDate.parse(expiryMatcher.group(1));
            }
        }
        if (id != null) {
            exceptions.add(new VulnerabilityException(id, statement, expiredAt));
        }
        return exceptions;
    }

    private static int count(String content, String value) {
        return content.split(Pattern.quote(value), -1).length - 1;
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(repositoryRoot().resolve(relativePath), StandardCharsets.UTF_8);
    }

    private static Path repositoryRoot() throws IOException {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve(".git")) && Files.isDirectory(current.resolve("repoguard-backend"))) {
                return current;
            }
            current = current.getParent();
        }
        fail("Cannot locate repository root from " + Path.of("").toAbsolutePath());
        throw new IllegalStateException("unreachable");
    }

    private record VulnerabilityException(String id, String statement, LocalDate expiredAt) {}
}
