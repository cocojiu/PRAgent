package com.repoguard.agent.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class IsolatedRealChainSmokeContractTest {

    @Test
    void smokeComposeAndWorkflowsAreValidYamlDocuments() throws IOException {
        Yaml yaml = new Yaml();

        assertInstanceOf(Map.class, yaml.load(read("docker-compose.smoke.yml")));
        assertInstanceOf(Map.class, yaml.load(read(".github/workflows/release-images.yml")));
        assertInstanceOf(Map.class, yaml.load(read(".github/workflows/real-chain-smoke.yml")));
    }

    @Test
    void smokeComposeKeepsInfrastructurePrivateAndProjectScoped() throws IOException {
        String compose = read("docker-compose.smoke.yml");

        assertThat(compose)
            .doesNotContain("container_name:")
            .doesNotContain("repoguard_mysql_data")
            .doesNotContain("repoguard_rabbitmq_data")
            .doesNotContain("3306:3306")
            .doesNotContain("5672:5672")
            .doesNotContain("15672:15672")
            .doesNotContain("REPOGUARD_OUTBOUND_LLM_ALLOWED_HOSTS: ${")
            .contains("REPOGUARD_OUTBOUND_LLM_ALLOWED_HOSTS: dashscope.aliyuncs.com,api.openai.com,token-plan-cn.xiaomimimo.com")
            .contains("127.0.0.1:${SMOKE_BACKEND_PORT}:8081");
    }

    @Test
    void lifecycleRunnerTargetsOnlyTheUniqueComposeProject() throws IOException {
        String lifecycle = read("performance/remote/run-isolated-real-chain-smoke.sh");
        String taskRunner = read("performance/remote/run-real-chain-smoke.sh");

        assertThat(lifecycle)
            .contains("repoguard-smoke-${run_id}-${run_attempt}")
            .contains("compose down --volumes --remove-orphans")
            .contains("trap cleanup EXIT")
            .contains("trap 'exit 130' INT TERM")
            .contains("--where=\"id = 1\"")
            .contains("--no-tablespaces")
            .contains("review_policy_config")
            .contains("SMOKE_WORKER_ENABLED=false")
            .contains("SMOKE_WORKER_ENABLED=true")
            .contains("smoke_stale_tasks_neutralized")
            .doesNotContain("docker system prune")
            .doesNotContain("repoguard-mysql")
            .doesNotContain("repoguard-backend");
        assertThat(taskRunner)
            .contains("mysql_container=\"${2:-}\"")
            .contains("compose_project=\"${3:-}\"")
            .doesNotContain("docker exec repoguard-mysql")
            .doesNotContain("docker exec repoguard-backend");
    }

    @Test
    void workflowsPublishAndInvokeCurrentIsolatedAssets() throws IOException {
        String release = read(".github/workflows/release-images.yml");
        String smoke = read(".github/workflows/real-chain-smoke.yml");

        assertThat(release)
            .contains("docker-compose.smoke.yml")
            .contains("performance/remote/run-isolated-real-chain-smoke.sh")
            .contains("performance/remote/run-real-chain-smoke.sh")
            .contains("chmod 0700");
        assertThat(smoke)
            .contains("inputs.backend_image")
            .contains("github.run_id")
            .contains("github.run_attempt")
            .contains("run-isolated-real-chain-smoke.sh")
            .contains("Verify production health after isolated smoke");
    }

    @Test
    void documentationAndReleaseBuildStayOnTheEnforcedBackendToolchain() throws IOException {
        String readme = read("README.md");
        String quality = read(".github/workflows/pr-quality.yml");
        String release = read(".github/workflows/release-images.yml");
        String wrapper = read("repoguard-backend/.mvn/wrapper/maven-wrapper.properties");
        String pom = read("repoguard-backend/pom.xml");
        String readiness = read("scripts/production-readiness-check.ps1");

        assertThat(readme)
            .contains("- Java 25")
            .contains("- JDK：25")
            .contains("- Maven：3.9.9+（低于 4.0.0）")
            .doesNotContain("Java 26")
            .doesNotContain("JDK：26");
        assertThat(release)
            .contains("java-version: \"25\"")
            .contains("run: ./mvnw -B verify")
            .doesNotContain("run: mvn -B package");
        assertThat(quality)
            .contains("name: Run backend production readiness slice")
            .contains("run: ./scripts/production-readiness-check.ps1 -Mode quick")
            .contains("run: ./mvnw -B verify")
            .contains("run: ./mvnw -B -Dtest=ProductionRuntimeContextIntegrationTest test")
            .doesNotContain("run: mvn ");
        assertThat(wrapper)
            .contains("distributionUrl=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip")
            .contains("distributionSha256Sum=5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce")
            .doesNotContain("maven.aliyun.com");
        assertThat(pom)
            .contains("<propertyName>jacoco.agent.argLine</propertyName>")
            .contains("@{jacoco.agent.argLine} -javaagent:${settings.localRepository}/org/mockito/mockito-core/${mockito.version}/mockito-core-${mockito.version}.jar");
        assertThat(readiness)
            .contains("$MavenWrapper = Join-Path $BackendDir \"mvnw\"")
            .contains("$MavenWrapper = Join-Path $BackendDir \"mvnw.cmd\"")
            .contains("-FilePath $MavenWrapper")
            .doesNotContain("-FilePath \"mvn\"");
    }

    @Test
    void releaseWorkflowSerializesProductionDeployments() throws IOException {
        String release = read(".github/workflows/release-images.yml");

        assertThat(release)
            .contains("group: release-images-${{ github.ref }}")
            .contains("group: production-deploy")
            .doesNotContain("cancel-in-progress: true");
        assertThat(release.split("cancel-in-progress: false", -1).length - 1).isEqualTo(2);
    }

    private static String read(String relativePath) throws IOException {
        return Files.readString(repositoryRoot().resolve(relativePath), StandardCharsets.UTF_8);
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.isDirectory(current.resolve(".git")) && Files.isDirectory(current.resolve("repoguard-backend"))) {
                return current;
            }
            current = current.getParent();
        }
        fail("Cannot locate repository root");
        throw new IllegalStateException("unreachable");
    }
}
