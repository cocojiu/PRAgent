package com.repoguard.agent.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

class ProductionObservabilityWorkflowContractTest {

    private static final List<String> UPGRADE_WORKFLOWS = List.of(
        ".github/workflows/production-observability-grafana-upgrade.yml",
        ".github/workflows/production-observability-log-stack-upgrade.yml"
    );

    @Test
    void upgradeWorkflowsDelegateTargetValidationAndKeepSecurityGates() throws IOException {
        Path root = repositoryRoot();
        String validationAction = read(root.resolve(".github/actions/validate-production-target/action.yml"));
        assertThat(validationAction)
            .contains(
                "DEPLOY_PATH must stay under /opt/repoguard.",
                "DEPLOY_HOST contains unsupported characters.",
                "DEPLOY_USER contains unsupported characters.",
                "set -euo pipefail"
            );

        for (String workflowPath : UPGRADE_WORKFLOWS) {
            String workflow = read(root.resolve(workflowPath));
            assertThat(workflow)
                .as("workflow contract for %s", workflowPath)
                .contains(
                    "uses: ./.github/actions/validate-production-target",
                    "uses: ./.github/actions/bootstrap-production-ssh",
                    "actions/checkout@9c091bb21b7c1c1d1991bb908d89e4e9dddfe3e0",
                    "permissions:\n  contents: read",
                    "environment: production"
                );
            assertThat(workflow.lines().count())
                .as("workflow size ratchet for %s", workflowPath)
                .isLessThan(1_400);
        }
    }

    private String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve(".git"))
                && Files.isDirectory(current.resolve("repoguard-backend"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root");
    }
}
