package com.repoguard.agent.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class WorkflowCatalogContractTest {

    @Test
    void catalogListsEveryWorkflowExactlyOnce() throws IOException {
        Path root = repositoryRoot();
        String catalog = read(root.resolve(".github/workflow-catalog.txt"));
        Set<String> ignoredWorkflowNames = ignoredWorkflowNames(root);
        List<String> workflowNames;
        try (Stream<Path> paths = Files.list(root.resolve(".github/workflows"))) {
            workflowNames = paths
                .filter(Files::isRegularFile)
                .filter(path -> !ignoredWorkflowNames.contains(path.getFileName().toString()))
                .map(path -> path.getFileName().toString())
                .sorted()
                .toList();
        }

        assertThat(workflowNames).hasSize(15);
        assertThat(catalog).contains("CI |", "Release |", "Maintenance |");
        for (String workflowName : workflowNames) {
            assertThat(catalog)
                .as("workflow catalog entry for %s", workflowName)
                .containsOnlyOnce("| .github/workflows/" + workflowName + " |");
        }
    }

    @Test
    void pullRequestAndReleaseTriggersUseTheConvergedEntrypoints() throws IOException {
        Path root = repositoryRoot();
        String prQuality = read(root.resolve(".github/workflows/pr-quality.yml"));
        String governance = read(root.resolve(".github/workflows/repository-governance.yml"));
        String release = read(root.resolve(".github/workflows/release-images.yml"));
        int releasePermissions = release.indexOf("\npermissions:");

        assertThat(prQuality)
            .contains(
                "name: Pull Request Quality",
                "name: Tracked file governance",
                "production-readiness-check.ps1 -Mode quick -SkipBackendTests"
            );
        assertThat(governance).doesNotContain("pull_request:");
        assertThat(release.substring(0, releasePermissions))
            .contains("workflow_dispatch:", "tags:", "- \"v*\"")
            .doesNotContain("PRAgent-test");
    }

    private static String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private static Set<String> ignoredWorkflowNames(Path root) throws IOException {
        Set<String> ignored = new HashSet<>();
        for (String line : Files.readAllLines(root.resolve(".gitignore"), StandardCharsets.UTF_8)) {
            String entry = line.trim();
            if (entry.startsWith(".github/workflows/") && !entry.endsWith("/")) {
                ignored.add(Path.of(entry).getFileName().toString());
            }
        }
        return ignored;
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        for (int depth = 0; depth < 8 && current != null; depth++) {
            if (Files.exists(current.resolve(".git"))
                && Files.exists(current.resolve("repoguard-frontend/package.json"))) {
                return current;
            }
            current = current.getParent();
        }
        return fail("Cannot locate repository root from " + Path.of("").toAbsolutePath());
    }
}
