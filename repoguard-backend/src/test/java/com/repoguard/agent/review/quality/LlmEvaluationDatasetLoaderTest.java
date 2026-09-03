package com.repoguard.agent.review.quality;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.config.JacksonConfig;
import com.repoguard.agent.config.LlmEvaluationRunProperties;
import com.repoguard.agent.review.quality.LlmEvaluationDatasetMetadata.DatasetKind;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LlmEvaluationDatasetLoaderTest {

    private final ObjectMapper objectMapper = new JacksonConfig().objectMapper();

    @Test
    void loadsAuthorizedRealPrManifestAndAllSamplesWithinTheBoundedRoot(@TempDir Path tempDir) throws Exception {
        Path datasetDirectory = Files.createDirectories(tempDir.resolve("dataset"));
        List<String> sampleFiles = new ArrayList<>();
        for (int index = 0; index < 50; index++) {
            String fileName = "samples/case-" + index + ".json";
            sampleFiles.add(fileName);
            Path samplePath = datasetDirectory.resolve(fileName);
            Files.createDirectories(samplePath.getParent());
            Files.writeString(samplePath, objectMapper.writeValueAsString(sample(index)));
        }
        LlmEvaluationDatasetLoader.Manifest manifest = manifest(sampleFiles);
        Files.writeString(datasetDirectory.resolve("manifest.json"), objectMapper.writeValueAsString(manifest));

        LlmEvaluationRunProperties properties = properties(tempDir);
        LlmEvaluationDatasetLoader.Dataset loaded = loader(properties).load(datasetDirectory.toString());

        assertThat(loaded.cases()).hasSize(50);
        assertThat(loaded.metadata().kind()).isEqualTo(DatasetKind.REAL_PR);
        assertThat(loaded.metadata().fixedRegressionSamples()).isEqualTo(25);
        assertThat(loaded.metadata().rollingObservationSamples()).isEqualTo(25);
        assertThat(loaded.version().versionKey()).contains("openai/gpt-test", "rules=rules-v1", "commit=commit-1");
        assertThat(loaded.minimumSamples()).isEqualTo(50);
        assertThat(loaded.cases().getFirst().files()).hasSize(1);
    }

    @Test
    void rejectsUnconfiguredOrOutOfRootDirectories(@TempDir Path tempDir) {
        LlmEvaluationRunProperties empty = new LlmEvaluationRunProperties();
        LlmEvaluationDatasetLoader emptyLoader = loader(empty);
        assertThatThrownBy(() -> emptyLoader.validateDirectory("dataset"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("尚未配置");

        LlmEvaluationRunProperties properties = properties(tempDir);
        LlmEvaluationDatasetLoader loader = loader(properties);
        assertThatThrownBy(() -> loader.validateDirectory(tempDir.toString()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("受控数据根目录");
        assertThatThrownBy(() -> loader.validateDirectory(".."))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("受控数据根目录");
        assertThatThrownBy(() -> loader.validateDirectory("missing"))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("不可用");
    }

    @Test
    void rejectsMalformedManifestAndSampleFiles(@TempDir Path tempDir) throws Exception {
        Path datasetDirectory = Files.createDirectories(tempDir.resolve("dataset"));
        LlmEvaluationRunProperties properties = properties(tempDir);
        LlmEvaluationDatasetLoader loader = loader(properties);

        Files.writeString(datasetDirectory.resolve("manifest.json"), "not-json");
        assertThatThrownBy(() -> loader.load(datasetDirectory.toString()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("清单格式无效");

        List<String> files = sampleFileNames();
        Files.writeString(datasetDirectory.resolve("manifest.json"), objectMapper.writeValueAsString(manifest(files)));
        Path samplePath = datasetDirectory.resolve(files.getFirst());
        Files.createDirectories(samplePath.getParent());
        Files.writeString(samplePath, "not-json");
        assertThatThrownBy(() -> loader.load(datasetDirectory.toString()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("样本格式无效");

        Files.delete(samplePath);
        List<String> missingFiles = sampleFileNames();
        missingFiles.set(0, "samples/missing.json");
        LlmEvaluationDatasetLoader.Manifest missingFileManifest = manifest(missingFiles);
        Files.writeString(datasetDirectory.resolve("manifest.json"), objectMapper.writeValueAsString(missingFileManifest));
        assertThatThrownBy(() -> loader.load(datasetDirectory.toString()))
            .isInstanceOf(BusinessException.class)
            .hasMessageContaining("样本文件不可用");
    }

    @Test
    void manifestAndSampleValidationRejectsUnsafeOrIncompleteMetadata(@TempDir Path tempDir) {
        LlmEvaluationRunProperties properties = properties(tempDir);
        LlmEvaluationDatasetLoader loader = loader(properties);
        List<String> files = List.of("case.json");

        assertThatThrownBy(() -> new LlmEvaluationDatasetLoader.Manifest(
            "dataset", "v1", DatasetKind.OFFLINE_SYNTHETIC, 2, 50, 25, 25, true, true, true,
            "a".repeat(64), "openai", "model", "prompt", "context", "schema", "chunk", BigDecimal.ONE,
            "rules", "commit", files, 50
        ).validate()).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new LlmEvaluationDatasetLoader.Manifest(
            "dataset", "v1", DatasetKind.REAL_PR, 1, 50, 25, 25, true, true, true,
            "bad", "openai", "model", "prompt", "context", "schema", "chunk", BigDecimal.ONE,
            "rules", "commit", files, 50
        ).validate()).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new LlmEvaluationDatasetLoader.Manifest(
            "dataset", "v1", DatasetKind.REAL_PR, 2, 50, 0, 50, true, true, true,
            "a".repeat(64), "openai", "model", "prompt", "context", "schema", "chunk", BigDecimal.valueOf(3),
            "rules", "commit", files, 20
        ).validate()).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new LlmEvaluationDatasetLoader.EvaluationCase(
            "case", "repo", "UNKNOWN", "java", "java", null, false, "NONE", "org", "repo", 0,
            "sha", "title", "main", List.of(), null, null, null, null
        ).validate()).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new LlmEvaluationDatasetLoader.EvaluationCase(
            "case", "repo", "FIXED_REGRESSION", "java", "java", null, true, "HIGH", "org", "repo", 1,
            "sha", "title", "main", List.of(new LlmEvaluationDatasetLoader.EvaluationFile("../secret", "M", 0, 0, "")),
            null, null, null, null
        ).validate()).isInstanceOf(BusinessException.class);
        assertThatThrownBy(() -> new LlmEvaluationDatasetLoader.EvaluationFile("file", "M", -1, 0, "").validate())
            .isInstanceOf(BusinessException.class);

        assertThat(loader).isNotNull();
    }

    private LlmEvaluationDatasetLoader loader(LlmEvaluationRunProperties properties) {
        return new LlmEvaluationDatasetLoader(objectMapper, properties);
    }

    private LlmEvaluationRunProperties properties(Path root) {
        LlmEvaluationRunProperties properties = new LlmEvaluationRunProperties();
        properties.setDataRoot(root.toString());
        properties.setMaxSampleBytes(1024 * 1024);
        properties.setMaxDatasetBytes(10 * 1024 * 1024);
        return properties;
    }

    private LlmEvaluationDatasetLoader.Manifest manifest(List<String> sampleFiles) {
        return new LlmEvaluationDatasetLoader.Manifest(
            "dataset-real-pr", "2026-09-03", DatasetKind.REAL_PR, 2, sampleFiles.size(),
            25, 25, true, true, true, "a".repeat(64), "openai", "gpt-test", "prompt-v1", "context-v1",
            "schema-v1", "chunk-v1", BigDecimal.valueOf(0.2), "rules-v1", "commit-1",
            "verifier-v1", "aggregation-v1", sampleFiles, 50
        );
    }

    private List<String> sampleFileNames() {
        List<String> files = new ArrayList<>();
        for (int index = 0; index < 50; index++) {
            files.add("samples/case-" + index + ".json");
        }
        return files;
    }

    private LlmEvaluationDatasetLoader.EvaluationCase sample(int index) {
        return new LlmEvaluationDatasetLoader.EvaluationCase(
            "case-" + index,
            "repo-" + (index % 2),
            index < 25 ? "FIXED_REGRESSION" : "ROLLING_OBSERVATION",
            "java",
            "jvm",
            null,
            false,
            "NONE",
            "org",
            "repo",
            index + 1,
            "sha-" + index,
            "title",
            "main",
            List.of(new LlmEvaluationDatasetLoader.EvaluationFile(
                "src/App.java", "modified", 1, 1, "@@ -1 +1 @@\n-old\n+new"
            )),
            Boolean.FALSE,
            Boolean.FALSE,
            Boolean.FALSE,
            Boolean.FALSE
        );
    }
}
