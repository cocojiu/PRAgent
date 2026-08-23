package com.repoguard.agent.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

class LlmCapacityConfigurationContractTest {

    private static final List<String> CAPACITY_VARIABLES = List.of(
        "REPOGUARD_REVIEW_PIPELINE_BUDGET_MS",
        "REPOGUARD_REVIEW_PIPELINE_MAX_TOTAL_CHUNKS",
        "REPOGUARD_REVIEW_PIPELINE_MAX_IN_FLIGHT_CHUNKS",
        "REPOGUARD_ASYNC_LLM_CHUNK_THREADS",
        "REPOGUARD_ASYNC_LLM_CHUNK_QUEUE_CAPACITY",
        "REPOGUARD_LLM_BULKHEAD_MAX_CONCURRENT_CALLS",
        "REPOGUARD_LLM_BULKHEAD_MAX_WAIT_MILLIS"
    );

    @Test
    void productionCapacityVariablesCrossTemplateComposeAndApplicationLayers() throws IOException {
        Path root = repositoryRoot();
        String environmentTemplate = read(root.resolve(".env.prod.example"));
        String applicationYml = read(root.resolve("repoguard-backend/src/main/resources/application.yml"));
        Map<String, Object> services = map(yaml(root.resolve("docker-compose.prod.yml")).get("services"));
        Map<String, Object> backendEnvironment = environment(services, "backend");
        Map<String, Object> workerEnvironment = environment(services, "backend-worker");

        for (String variable : CAPACITY_VARIABLES) {
            assertThat(environmentTemplate)
                .as(variable + " must be documented in .env.prod.example")
                .contains(variable + "=");
            assertThat(applicationYml)
                .as(variable + " must be consumed by application.yml")
                .contains("${" + variable + ":");
            assertThat(backendEnvironment)
                .as(variable + " must reach the monolith/API container")
                .containsKey(variable);
            assertThat(workerEnvironment)
                .as(variable + " must reach the split worker container")
                .containsKey(variable);
        }
    }

    @Test
    void smokeComposePinsTheSameBoundedCapacityDefaults() throws IOException {
        Path root = repositoryRoot();
        Map<String, Object> services = map(yaml(root.resolve("docker-compose.smoke.yml")).get("services"));
        Map<String, Object> backendEnvironment = environment(services, "backend");

        assertThat(backendEnvironment)
            .containsEntry("REPOGUARD_REVIEW_EXECUTION_BUDGET_MS", 600000)
            .containsEntry("REPOGUARD_REVIEW_PIPELINE_BUDGET_MS", 480000)
            .containsEntry("REPOGUARD_REVIEW_PIPELINE_MAX_TOTAL_CHUNKS", 64)
            .containsEntry("REPOGUARD_REVIEW_PIPELINE_MAX_IN_FLIGHT_CHUNKS", 2)
            .containsEntry("REPOGUARD_ASYNC_LLM_CHUNK_THREADS", 2)
            .containsEntry("REPOGUARD_ASYNC_LLM_CHUNK_QUEUE_CAPACITY", 100)
            .containsEntry("REPOGUARD_LLM_BULKHEAD_MAX_CONCURRENT_CALLS", 2)
            .containsEntry("REPOGUARD_LLM_BULKHEAD_MAX_WAIT_MILLIS", 250);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> environment(Map<String, Object> services, String service) {
        return map(map(services.get(service)).get("environment"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> yaml(Path path) throws IOException {
        return (Map<String, Object>) new Yaml().load(read(path));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    private String read(Path path) throws IOException {
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    private Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null) {
            if (Files.exists(current.resolve(".git")) && Files.isDirectory(current.resolve("repoguard-frontend"))) {
                return current;
            }
            current = current.getParent();
        }
        fail("Cannot locate repository root from " + Path.of("").toAbsolutePath());
        throw new IllegalStateException("unreachable");
    }
}
