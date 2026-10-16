package com.repoguard.agent.quality;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
    void productionEmbeddedPythonParsesBeforeAnyWorkflowCanReachProduction() throws Exception {
        Map<String, Object> workflow = new Yaml().load(read(repositoryRoot()
            .resolve(".github/workflows/production-observability-log-stack-upgrade.yml")));
        String python = System.getProperty("repoguard.python.executable",
            System.getProperty("os.name").startsWith("Windows") ? "python" : "python3");
        Pattern heredoc = Pattern.compile("(?s)<<'PY'[^\\n]*\\n(.*?)\\nPY(?:\\n|$)");
        int verified = 0;
        for (Object job : mapping(workflow.get("jobs")).values()) {
            for (Map<String, Object> step : steps(mapping(job))) {
                Matcher matcher = heredoc.matcher(String.valueOf(step.getOrDefault("run", "")));
                while (matcher.find()) {
                    Process process = new ProcessBuilder(python, "-B", "-c", "import ast,sys; ast.parse(sys.stdin.read())")
                        .redirectErrorStream(true).start();
                    try (var input = process.getOutputStream()) {
                        input.write(matcher.group(1).getBytes(StandardCharsets.UTF_8));
                    }
                    boolean completed = process.waitFor(15, TimeUnit.SECONDS);
                    if (!completed) process.destroyForcibly();
                    assertThat(completed).isTrue();
                    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                    assertThat(process.exitValue()).withFailMessage(output).isZero();
                    verified++;
                }
            }
        }
        assertThat(verified).isEqualTo(5);
    }

    @Test
    void lokiProductionRequiresVerifiedSourceAndCurrentStrictScanBeforeEnvironmentAccess() throws IOException {
        Map<String, Object> workflow = new Yaml().load(read(repositoryRoot()
            .resolve(".github/workflows/production-observability-log-stack-upgrade.yml")));
        assertThat(mapping(workflow.get("permissions"))).isEqualTo(Map.of("contents", "read"));
        Map<String, Object> jobs = mapping(workflow.get("jobs"));
        Map<String, Object> source = mapping(jobs.get("source"));
        assertThat(source).doesNotContainKeys("environment", "secrets");
        assertThat(mapping(source.get("permissions"))).isEqualTo(Map.of("actions", "read", "contents", "read"));
        Map<String, Object> scan = steps(source).stream()
            .filter(step -> String.valueOf(step.get("name")).startsWith("Enforce current zero"))
            .map(step -> mapping(step.get("with"))).findFirst().orElseThrow();
        assertThat(scan.get("exit-code")).isEqualTo("1");
        assertThat(scan.get("ignore-unfixed")).isEqualTo(false);
        assertThat(scan.get("severity")).isEqualTo("HIGH,CRITICAL");
        assertThat(scan.get("image-ref")).isEqualTo("${{ steps.identity.outputs.image_reference }}");
        Map<String, Object> production = mapping(jobs.get("production"));
        assertThat(production.get("needs")).isEqualTo("source");
        assertThat(production.get("if")).isEqualTo(
            "${{ github.event_name == 'workflow_dispatch' && (inputs.action == 'preflight' || github.ref == 'refs/heads/main') }}");
        assertThat(production.get("environment")).isEqualTo("production");
        assertThat(mapping(production.get("permissions"))).isEqualTo(Map.of(
            "actions", "read", "contents", "read", "attestations", "write", "id-token", "write"));
    }

    @Test
    void lokiPreflightKeepsProductionReadOnlyAndWritesRequireExplicitMainConfirmation() throws IOException {
        Map<String, Object> workflow = new Yaml().load(read(repositoryRoot()
            .resolve(".github/workflows/production-observability-log-stack-upgrade.yml")));
        Map<String, Object> jobs = mapping(workflow.get("jobs"));
        String validation = String.valueOf(steps(mapping(jobs.get("source"))).stream()
            .filter(step -> "Validate explicit request".equals(step.get("name"))).findFirst().orElseThrow().get("run"));
        assertThat(validation).contains("os.environ['GITHUB_REF'] == 'refs/heads/main'",
            "UPGRADE_LOKI_SECURITY", "ROLLBACK_LOKI_SECURITY");
        List<Map<String, Object>> production = steps(mapping(jobs.get("production")));
        Map<String, Object> preflight = production.stream()
            .filter(step -> "Read production preflight without installing files".equals(step.get("name")))
            .findFirst().orElseThrow();
        assertThat(String.valueOf(preflight.get("run"))).contains("production_preflight", "python3 -")
            .doesNotContain("scp ", "mkdir ", "docker pull", "docker run");
        Map<String, Object> operation = production.stream()
            .filter(step -> "operation".equals(step.get("id"))).findFirst().orElseThrow();
        assertThat(operation.get("if")).isEqualTo("inputs.action != 'preflight'");
        assertThat(String.valueOf(operation.get("run"))).contains(
            "test \"${GITHUB_REF}\" = 'refs/heads/main'", "upgrade:UPGRADE_LOKI_SECURITY",
            "rollback:ROLLBACK_LOKI_SECURITY", "scripts/loki-deploy.py", "--manifest-sha '${MANIFEST_SHA}'");
        Map<String, Object> recovery = mapping(mapping(new Yaml().load(read(repositoryRoot()
            .resolve(".github/workflows/production-observability-security.yml")))).get("jobs"));
        Map<String, Object> rehearsal = mapping(recovery.get("loki-recovery-rehearsal"));
        Map<String, Object> fixtureScan = steps(rehearsal).stream()
            .filter(step -> String.valueOf(step.get("name")).startsWith("Enforce zero HIGH"))
            .map(step -> mapping(step.get("with"))).findFirst().orElseThrow();
        assertThat(fixtureScan.get("exit-code")).isEqualTo("1");
        assertThat(fixtureScan.get("ignore-unfixed")).isEqualTo(false);
    }

    @Test
    void encryptedRecoveryRunsWithoutProductionAccessAndRequiresActualEvidence() throws IOException {
        String content = read(repositoryRoot().resolve(".github/workflows/production-observability-security.yml"));
        Map<String, Object> workflow = new Yaml().load(content);
        Map<String, Object> recovery = mapping(mapping(workflow.get("jobs")).get("loki-recovery-rehearsal"));
        assertThat(mapping(recovery.get("permissions"))).isEqualTo(Map.of("contents", "read"));
        assertThat(recovery).doesNotContainKeys("environment", "secrets");
        assertThat(recovery.get("needs")).isEqualTo("resolve");
        assertThat(recovery.get("if")).isEqualTo(
            "${{ github.event_name == 'pull_request' || (github.event_name == 'workflow_dispatch' && inputs.verify_loki_recovery) }}"
        );
        String scripts = steps(recovery).stream().map(step -> String.valueOf(step.getOrDefault("run", "")))
            .reduce("", (left, right) -> left + right);
        assertThat(scripts).contains("LokiSnapshotLinuxIntegrationTest", "('failures', 'errors', 'skipped')",
            "result['encryptedBackupRestored']", "result['realAlloyCursorRetained']",
            "result['originalVolumesUnchanged']", "result['productionWrites'] == result['publishedPorts'] == 0")
            .doesNotContain("ssh ", "DEPLOY_HOST", "docker compose up");
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
            "persistence['runtimeConfigSha256'] == hashlib.sha256(runtime_config.read_bytes()).hexdigest()",
            "persistence['memoryLimitBytes'] == 384 * 1024 * 1024",
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
