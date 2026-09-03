package com.repoguard.agent.review.quality;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.config.LlmEvaluationRunProperties;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

/** Loads an authorized evaluation manifest without persisting its source or patch payloads. */
@Component
public class LlmEvaluationDatasetLoader {

    private static final String MANIFEST_FILE = "manifest.json";
    private static final long MAX_MANIFEST_BYTES = 1L * 1024L * 1024L;
    private static final int MAX_SAMPLES = 100;
    private static final int MAX_FILES_PER_SAMPLE = 100;
    private static final int MAX_PATCH_CHARS = 1_000_000;

    private final ObjectMapper objectMapper;
    private final LlmEvaluationRunProperties properties;

    public LlmEvaluationDatasetLoader(ObjectMapper objectMapper, LlmEvaluationRunProperties properties) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    /** Resolves and validates a directory before a job is queued. */
    public Path validateDirectory(String requestedDirectory) {
        Path root = configuredRoot();
        Path requested;
        try {
            requested = Path.of(requireText(requestedDirectory, "dataDirectory"));
        } catch (InvalidPathException ex) {
            throw invalidData("评估数据目录路径无效");
        }
        Path candidate = requested.isAbsolute() ? requested : root.resolve(requested);
        try {
            Path realRoot = root.toRealPath();
            Path realCandidate = candidate.toRealPath();
            if (realCandidate.equals(realRoot) || !realCandidate.startsWith(realRoot)
                || !Files.isDirectory(realCandidate)) {
                throw invalidData("评估数据目录必须位于受控数据根目录内");
            }
            return realCandidate;
        } catch (IOException ex) {
            throw invalidData("评估数据目录不可用");
        }
    }

    public Dataset load(String requestedDirectory) {
        Path directory = validateDirectory(requestedDirectory);
        Path manifestPath = resolveFile(directory, MANIFEST_FILE);
        String manifestJson = readBounded(manifestPath, MAX_MANIFEST_BYTES, "评估数据清单不可用");
        Manifest manifest;
        try {
            manifest = objectMapper.readValue(manifestJson, Manifest.class);
        } catch (Exception ex) {
            throw invalidData("评估数据清单格式无效");
        }
        manifest.validate();

        long totalBytes = 0L;
        List<EvaluationCase> cases = new java.util.ArrayList<>(manifest.sampleFiles().size());
        for (String sampleFile : manifest.sampleFiles()) {
            Path samplePath = resolveFile(directory, sampleFile);
            long size = fileSize(samplePath);
            totalBytes = safeAdd(totalBytes, size);
            if (totalBytes > positive(properties.getMaxDatasetBytes(), 512L * 1024L * 1024L)) {
                throw invalidData("评估数据集超过大小上限");
            }
            String sampleJson = readBounded(
                samplePath,
                positive(properties.getMaxSampleBytes(), 10L * 1024L * 1024L),
                "评估样本超过大小上限"
            );
            try {
                EvaluationCase sample = objectMapper.readValue(sampleJson, EvaluationCase.class);
                sample.validate();
                cases.add(sample);
            } catch (Exception ex) {
                throw invalidData("评估样本格式无效");
            }
        }
        if (cases.size() != manifest.sampleCount()) {
            throw invalidData("评估样本数量与清单不一致");
        }
        LlmEvaluationDatasetMetadata metadata = new LlmEvaluationDatasetMetadata(
            manifest.datasetId(), manifest.datasetVersion(), manifest.datasetKind(), manifest.sourceRepositoryCount(),
            manifest.sampleCount(), manifest.fixedRegressionSamples(), manifest.rollingObservationSamples(),
            manifest.authorized(), manifest.anonymized(), manifest.humanReviewed(), manifest.sampleFingerprint()
        );
        LlmEvaluationVersion version = new LlmEvaluationVersion(
            manifest.provider(), manifest.model(), manifest.promptVersion(), manifest.contextVersion(),
            manifest.schemaVersion(), manifest.chunkPolicyVersion(), manifest.temperature(), manifest.ruleVersion(),
            manifest.codeRevision(), manifest.verifierVersion(), manifest.aggregationVersion()
        );
        return new Dataset(metadata, version, List.copyOf(cases), manifest.minimumSamples());
    }

    private Path configuredRoot() {
        String configured = properties.getDataRoot();
        if (configured == null || configured.isBlank()) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "评估数据目录尚未配置");
        }
        try {
            Path root = Path.of(configured).toAbsolutePath().normalize();
            if (!Files.isDirectory(root)) {
                throw invalidData("评估数据根目录不可用");
            }
            return root;
        } catch (InvalidPathException ex) {
            throw invalidData("评估数据根目录配置无效");
        }
    }

    private Path resolveFile(Path directory, String relativePath) {
        String value = requireText(relativePath, "sampleFile");
        Path candidate;
        try {
            candidate = directory.resolve(value).normalize();
        } catch (InvalidPathException ex) {
            throw invalidData("评估样本路径无效");
        }
        try {
            Path real = candidate.toRealPath();
            if (!real.startsWith(directory) || Files.isDirectory(real) || Files.isSymbolicLink(candidate)) {
                throw invalidData("评估样本路径越界或不是文件");
            }
            return real;
        } catch (IOException ex) {
            throw invalidData("评估样本文件不可用");
        }
    }

    private String readBounded(Path path, long maxBytes, String message) {
        long size = fileSize(path);
        if (size > maxBytes || size > Integer.MAX_VALUE) {
            throw invalidData(message);
        }
        try {
            return Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw invalidData(message);
        }
    }

    private long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException ex) {
            throw invalidData("评估数据文件不可用");
        }
    }

    private long safeAdd(long left, long right) {
        if (right < 0 || left > Long.MAX_VALUE - right) return Long.MAX_VALUE;
        return left + right;
    }

    private long positive(long value, long fallback) {
        return value > 0 ? value : fallback;
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw invalidData("评估" + field + "不能为空");
        return value.trim();
    }

    private static BusinessException invalidData(String message) {
        return new BusinessException(ErrorCode.BAD_REQUEST, message);
    }

    public record Dataset(
        LlmEvaluationDatasetMetadata metadata,
        LlmEvaluationVersion version,
        List<EvaluationCase> cases,
        int minimumSamples
    ) {
        public Dataset {
            cases = cases == null ? List.of() : List.copyOf(cases);
            minimumSamples = Math.max(1, minimumSamples);
        }
    }

    /** Manifest is metadata-only; sample payloads are referenced by relative file names. */
    public record Manifest(
        String datasetId,
        String datasetVersion,
        LlmEvaluationDatasetMetadata.DatasetKind datasetKind,
        int sourceRepositoryCount,
        int sampleCount,
        int fixedRegressionSamples,
        int rollingObservationSamples,
        boolean authorized,
        boolean anonymized,
        boolean humanReviewed,
        String sampleFingerprint,
        String provider,
        String model,
        String promptVersion,
        String contextVersion,
        String schemaVersion,
        String chunkPolicyVersion,
        BigDecimal temperature,
        String ruleVersion,
        String codeRevision,
        String verifierVersion,
        String aggregationVersion,
        List<String> sampleFiles,
        int minimumSamples
    ) {
        public Manifest(String datasetId, String datasetVersion,
            LlmEvaluationDatasetMetadata.DatasetKind datasetKind, int sourceRepositoryCount, int sampleCount,
            int fixedRegressionSamples, int rollingObservationSamples, boolean authorized, boolean anonymized,
            boolean humanReviewed, String sampleFingerprint, String provider, String model, String promptVersion,
            String contextVersion, String schemaVersion, String chunkPolicyVersion, BigDecimal temperature,
            String ruleVersion, String codeRevision, List<String> sampleFiles, int minimumSamples) {
            this(datasetId, datasetVersion, datasetKind, sourceRepositoryCount, sampleCount, fixedRegressionSamples,
                rollingObservationSamples, authorized, anonymized, humanReviewed, sampleFingerprint, provider, model,
                promptVersion, contextVersion, schemaVersion, chunkPolicyVersion, temperature, ruleVersion,
                codeRevision, "legacy-unknown", "legacy-unknown", sampleFiles, minimumSamples);
        }

        public void validate() {
            requireText(datasetId, "datasetId");
            requireText(datasetVersion, "datasetVersion");
            if (datasetKind != LlmEvaluationDatasetMetadata.DatasetKind.REAL_PR
                || sampleFiles == null || sampleFiles.isEmpty() || sampleFiles.size() > MAX_SAMPLES) {
                throw invalidData("评估数据清单样本文件列表无效");
            }
            if (sampleCount < 50 || sampleCount > MAX_SAMPLES || sampleFiles.size() != sampleCount) {
                throw invalidData("评估数据清单样本数量无效");
            }
            if (sourceRepositoryCount < 2 || sourceRepositoryCount > 3 || fixedRegressionSamples < 1
                || rollingObservationSamples < 1 || fixedRegressionSamples + rollingObservationSamples != sampleCount) {
                throw invalidData("评估数据清单分片或仓库数量无效");
            }
            requireText(sampleFingerprint, "sampleFingerprint");
            if (!sampleFingerprint.matches("(?i)[0-9a-f]{64}")) {
                throw invalidData("评估数据清单指纹必须是 SHA-256");
            }
            requireText(provider, "provider");
            requireText(model, "model");
            requireText(promptVersion, "promptVersion");
            requireText(contextVersion, "contextVersion");
            requireText(schemaVersion, "schemaVersion");
            requireText(chunkPolicyVersion, "chunkPolicyVersion");
            requireText(ruleVersion, "ruleVersion");
            requireText(codeRevision, "codeRevision");
            requireText(verifierVersion, "verifierVersion");
            requireText(aggregationVersion, "aggregationVersion");
            if (temperature == null || temperature.signum() < 0 || temperature.compareTo(new BigDecimal("2.0")) > 0) {
                throw invalidData("评估温度参数无效");
            }
            if (minimumSamples < 30 || minimumSamples > 100) {
                throw invalidData("评估最小样本数必须在 30 到 100 之间");
            }
        }
    }

    /** One sample contains raw diff only in the external, authorized directory. */
    public record EvaluationCase(
        String caseId,
        String sourceRepositoryKey,
        String split,
        String language,
        String fileTypeGroup,
        String expectedLocationKey,
        boolean expectedFinding,
        String expectedSeverity,
        String organization,
        String repository,
        int prNumber,
        String headSha,
        String title,
        String branch,
        List<EvaluationFile> files,
        Boolean usefulComment,
        Boolean commentPublished,
        Boolean commentFixed,
        Boolean commentIgnored
    ) {
        public void validate() {
            requireText(caseId, "caseId");
            requireText(sourceRepositoryKey, "sourceRepositoryKey");
            requireText(split, "split");
            requireText(language, "language");
            requireText(fileTypeGroup, "fileTypeGroup");
            requireText(organization, "organization");
            requireText(repository, "repository");
            requireText(headSha, "headSha");
            if (prNumber < 1 || files == null || files.isEmpty() || files.size() > MAX_FILES_PER_SAMPLE) {
                throw invalidData("评估样本 PR 或文件列表无效");
            }
            if (expectedFinding && (expectedLocationKey == null || expectedLocationKey.isBlank())) {
                throw invalidData("带 Finding 的评估样本必须提供人工位置标签");
            }
            files.forEach(EvaluationFile::validate);
        }
    }

    public record EvaluationFile(
        String filename,
        String status,
        int additions,
        int deletions,
        String patch
    ) {
        public void validate() {
            requireText(filename, "filename");
            requireText(status, "status");
            if (filename.startsWith("/") || filename.contains("..")) throw invalidData("评估文件路径无效");
            if (additions < 0 || deletions < 0 || patch == null || patch.length() > MAX_PATCH_CHARS) {
                throw invalidData("评估文件 Diff 超出限制");
            }
        }
    }
}
