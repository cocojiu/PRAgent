package com.repoguard.agent.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

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
                    "actions/checkout@3d3c42e5aac5ba805825da76410c181273ba90b1",
                    "permissions:\n  contents: read",
                    "environment: production"
                );
            assertThat(workflow.lines().count())
                .as("workflow size ratchet for %s", workflowPath)
                .isLessThan(1_400);
        }
    }

    @Test
    void lokiPublicationKeepsWritesBehindManualMainAndIndependentValidation() throws IOException {
        Map<String, Object> workflow = new Yaml().load(read(repositoryRoot()
            .resolve(".github/workflows/production-observability-security.yml")));
        assertThat(mapping(workflow.get("permissions"))).isEqualTo(Map.of("contents", "read"));
        Map<String, Object> jobs = mapping(workflow.get("jobs"));
        Map<String, Object> candidate = mapping(jobs.get("loki-security-candidate"));
        assertThat(mapping(candidate.get("permissions"))).isEqualTo(Map.of("contents", "read"));
        Map<String, Object> transfer = mapping(jobs.get("loki-candidate-transfer"));
        assertThat(mapping(transfer.get("permissions")))
            .isEqualTo(Map.of("actions", "read", "contents", "read"));

        Map<String, Object> publisher = mapping(jobs.get("loki-candidate-publish"));
        assertThat(publisher.get("if")).isEqualTo(
            "${{ github.event_name == 'workflow_dispatch' && inputs.publish_loki_candidate == true && github.ref == 'refs/heads/main' }}"
        );
        assertThat(((List<?>) publisher.get("needs")).stream().map(String::valueOf).toList())
            .containsExactly("resolve", "loki-security-candidate", "loki-candidate-transfer");
        assertThat(mapping(publisher.get("permissions"))).isEqualTo(Map.of(
            "actions", "read", "contents", "read", "packages", "write",
            "attestations", "write", "id-token", "write"
        ));
        assertThat(publisher).doesNotContainKeys("environment", "secrets");
        String content = read(repositoryRoot().resolve(".github/workflows/production-observability-security.yml"));
        assertThat(content).doesNotContain("bootstrap-production-ssh", "DEPLOY_HOST", "SSH_PRIVATE_KEY");
    }

    @Test
    void lokiReleaseRequiresStrictScanAndEvidenceBeforeLoadingTheImage() throws IOException {
        Map<String, Object> workflow = new Yaml().load(read(repositoryRoot()
            .resolve(".github/workflows/production-observability-security.yml")));
        Map<String, Object> candidate = mapping(mapping(workflow.get("jobs")).get("loki-security-candidate"));
        Map<String, Object> scan = steps(candidate).stream()
            .map(step -> mapping(step.getOrDefault("with", Map.of())))
            .filter(options -> "repoguard-loki-candidate:ci".equals(options.get("image-ref")))
            .filter(options -> "json".equals(options.get("format")))
            .findFirst().orElseThrow();
        assertThat(scan.get("exit-code")).isEqualTo("1");
        assertThat(scan.get("ignore-unfixed")).isEqualTo(false);
        assertThat(scan.get("severity")).isEqualTo("HIGH,CRITICAL");

        Map<String, Object> action = new Yaml().load(read(repositoryRoot()
            .resolve(".github/actions/verify-loki-candidate/action.yml")));
        String verifier = String.valueOf(steps(mapping(action.get("runs"))).getFirst().get("run"));
        int checksumGate = verifier.indexOf("assert digest == match[1]");
        int loading = verifier.indexOf("subprocess.run(['docker', 'image', 'load'");
        assertThat(checksumGate).isGreaterThanOrEqualTo(0).isLessThan(loading);
        assertThat(verifier).contains(
            "scan['Metadata']['ImageID'] == identity",
            "persistence['productionConnections'] == persistence['publishedPorts'] == 0",
            "phases[1]['retainedLogs'] == phases[2]['retainedLogs']",
            "loaded[0]['Id'] == identity",
            "'go1.26.9' in version"
        );
    }

    @Test
    void lokiPublisherLoadsTheCheckedArtifactAndAttestsBeforeCreatingAReleaseManifest() throws IOException {
        Map<String, Object> workflow = new Yaml().load(read(repositoryRoot()
            .resolve(".github/workflows/production-observability-security.yml")));
        Map<String, Object> publisher = mapping(mapping(workflow.get("jobs")).get("loki-candidate-publish"));
        List<Map<String, Object>> steps = steps(publisher);
        List<String> names = steps.stream().map(step -> String.valueOf(step.get("name"))).toList();
        assertThat(names.indexOf("Verify transferred candidate"))
            .isGreaterThanOrEqualTo(0).isLessThan(names.indexOf("Log in to GHCR"));
        assertThat(names.indexOf("Attest checked SBOM"))
            .isGreaterThanOrEqualTo(0).isLessThan(names.indexOf("Create checked Loki source manifest"));
        assertThat(names.indexOf("Attest checked Loki source manifest"))
            .isGreaterThanOrEqualTo(0).isLessThan(names.indexOf("Preserve signed source manifest"));
        String scripts = steps.stream().map(step -> String.valueOf(step.getOrDefault("run", "")))
            .reduce("", (left, right) -> left + right);
        assertThat(scripts)
            .contains("gh run download \"${GITHUB_RUN_ID}\"", "${GITHUB_RUN_ATTEMPT}",
                "docker image pull \"${repository}@${digest}\"", "${CHECKED_IMAGE_ID}")
            .doesNotContain("docker build", "docker compose", "ssh ");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> mapping(Object value) {
        return (Map<String, Object>) value;
    }

    private List<Map<String, Object>> steps(Map<String, Object> job) {
        return ((List<?>) job.get("steps")).stream().map(this::mapping).toList();
    }

    private String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.exists(current.resolve(".git"))
                && Files.isDirectory(current.resolve("repoguard-backend"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate repository root");
    }
}
