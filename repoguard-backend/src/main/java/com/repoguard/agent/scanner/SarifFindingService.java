package com.repoguard.agent.scanner;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.SarifExportDto;
import com.repoguard.agent.dto.SarifImportRequest;
import com.repoguard.agent.dto.SarifImportResponse;
import com.repoguard.agent.dto.SarifImportedFindingDto;
import com.repoguard.agent.entity.ReviewExecutionAttempt;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.entity.ReviewTask;
import com.repoguard.agent.mapper.ReviewExecutionAttemptMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import com.repoguard.agent.review.ReviewFindingIdentity;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import com.repoguard.agent.mapper.ReviewFindingMapper.SarifImportBatchRow;
import com.repoguard.agent.tenancy.TenantContext;
@Service
public class SarifFindingService {
    private static final String SARIF_VERSION = "2.1.0";
    private static final int MAX_RUNS = 20;
    private static final int MAX_RESULTS = 5_000;
    private static final int MAX_RULE_ID_LENGTH = 64;
    private final ObjectMapper objectMapper;
    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewFindingMapper reviewFindingMapper;
    private final ReviewExecutionAttemptMapper reviewExecutionAttemptMapper;
    public SarifFindingService(
        ObjectMapper objectMapper,
        ReviewTaskMapper reviewTaskMapper,
        ReviewFindingMapper reviewFindingMapper,
        ReviewExecutionAttemptMapper reviewExecutionAttemptMapper
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.reviewTaskMapper = Objects.requireNonNull(reviewTaskMapper, "reviewTaskMapper");
        this.reviewFindingMapper = Objects.requireNonNull(reviewFindingMapper, "reviewFindingMapper");
        this.reviewExecutionAttemptMapper = Objects.requireNonNull(
            reviewExecutionAttemptMapper,
            "reviewExecutionAttemptMapper"
        );
    }
    @Transactional
    public SarifImportResponse importFindings(Long taskId, SarifImportRequest request) {
        ReviewTask task = requireTask(taskId);
        if (request == null || !StringUtils.hasText(request.content())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "SARIF content is required");
        }
        ReviewExecutionAttempt attempt = requireCurrentAttempt(taskId, task);
        JsonNode root = parse(request.content());
        if (!SARIF_VERSION.equals(root.path("version").asText())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Only SARIF version 2.1.0 is supported");
        }
        JsonNode runs = root.path("runs");
        if (!runs.isArray() || runs.isEmpty() || runs.size() > MAX_RUNS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "SARIF runs must be a non-empty array of at most 20 items");
        }
        String commitSha = resolveCommitSha(task, attempt);
        ToolMetadata tool = toolMetadata(runs);
        String fingerprint = sha256(request.content());
        SarifImportBatchRow existing = findBatch(
            taskId, attempt.getId(), tool.name(), tool.version(), commitSha, fingerprint);
        if (existing != null) {
            return responseFromBatch(taskId, existing);
        }
        List<SarifImportedFindingDto> imported = new ArrayList<>();
        List<ParsedResult> parsedResults = new ArrayList<>();
        int skipped = 0;
        int totalResults = 0;
        for (JsonNode run : runs) {
            Map<String, String> ruleHelp = ruleHelp(run.path("tool").path("driver").path("rules"));
            JsonNode results = run.path("results");
            if (!results.isArray()) {
                continue;
            }
            totalResults += results.size();
            if (totalResults > MAX_RESULTS) {
                throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "SARIF contains too many results");
            }
            for (JsonNode result : results) {
                ParsedResult parsed = parseResult(result, ruleHelp);
                if (parsed == null) {
                    skipped++;
                    continue;
                }
                parsedResults.add(parsed);
                imported.add(new SarifImportedFindingDto(
                    parsed.ruleId(), parsed.filePath(), parsed.lineNumber(), parsed.severity(), parsed.message()
                ));
            }
        }
        SarifImportBatchRow batch = new SarifImportBatchRow();
        batch.setTenantId(TenantContext.currentTenantIdOrDefault());
        batch.setTaskId(taskId);
        batch.setAttemptId(attempt.getId());
        batch.setToolName(tool.name());
        batch.setToolVersion(tool.version());
        batch.setCommitSha(commitSha);
        batch.setContentFingerprint(fingerprint);
        batch.setStatus("ACTIVE");
        batch.setImportedCount(imported.size());
        batch.setSkippedCount(skipped);
        batch.setCreatedAt(java.time.LocalDateTime.now());
        batch.setUpdatedAt(batch.getCreatedAt());
        try {
            reviewFindingMapper.insertSarifImportBatch(batch);
        } catch (DuplicateKeyException ex) {
            SarifImportBatchRow raced = findBatch(
                taskId, attempt.getId(), tool.name(), tool.version(), commitSha, fingerprint);
            if (raced != null) {
                return responseFromBatch(taskId, raced);
            }
            throw ex;
        }
        if (batch.getId() == null) {
            throw new IllegalStateException("SARIF import batch id was not generated");
        }
        supersedePreviousBatches(taskId, attempt.getId(), tool, commitSha, fingerprint);
        for (ParsedResult parsed : parsedResults) {
            reviewFindingMapper.insert(toEntity(taskId, attempt.getId(), batch.getId(), parsed));
        }
        return new SarifImportResponse(taskId, imported.size(), skipped, List.copyOf(imported));
    }
    public SarifExportDto exportFindings(Long taskId) {
        requireTask(taskId);
        return SarifExportBuilder.export(taskId, reviewFindingMapper);
    }

    /** Returns the canonical fingerprint used by the durable SARIF batch identity. */
    public String contentFingerprint(String content) {
        return sha256(content == null ? "" : content);
    }

    private JsonNode parse(String content) {
        try {
            return objectMapper.readTree(content);
        } catch (Exception ex) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Invalid SARIF JSON");
        }
    }
    private ParsedResult parseResult(JsonNode result, Map<String, String> ruleHelp) {
        String ruleId = result.path("ruleId").asText("").trim();
        if (!StringUtils.hasText(ruleId) || ruleId.length() > MAX_RULE_ID_LENGTH) {
            return null;
        }
        JsonNode location = result.path("locations").isArray() && result.path("locations").size() > 0
            ? result.path("locations").get(0).path("physicalLocation")
            : null;
        if (location == null || location.isMissingNode()) {
            return null;
        }
        String filePath = sanitizePath(location.path("artifactLocation").path("uri").asText(""));
        if (!StringUtils.hasText(filePath)) {
            return null;
        }
        int line = location.path("region").path("startLine").asInt(0);
        if (line < 1) {
            return null;
        }
        String message = result.path("message").path("text").asText("").trim();
        if (!StringUtils.hasText(message)) {
            message = ruleHelp.getOrDefault(ruleId, "SARIF scanner reported a finding");
        }
        return new ParsedResult(ruleId, filePath, line, severity(result.path("level").asText("")), message,
            ruleHelp.getOrDefault(ruleId, ""));
    }
    private ReviewFinding toEntity(Long taskId, Long attemptId, Long batchId, ParsedResult parsed) {
        ReviewFinding finding = new ReviewFinding();
        finding.setTaskId(taskId);
        finding.setAttemptId(attemptId);
        finding.setSourceBatchId(batchId);
        finding.setCurrentAttempt(true);
        finding.setCategory("FINDING");
        finding.setSeverity(parsed.severity());
        finding.setSource("SARIF");
        finding.setRuleId(parsed.ruleId());
        finding.setFilePath(parsed.filePath());
        finding.setLineNumber(parsed.lineNumber());
        finding.setMessage(parsed.message());
        finding.setRecommendation(parsed.help());
        finding.setConfidence("HIGH");
        finding.setEvidence(parsed.message());
        finding.setImpact("third_party_scan");
        finding.setFixExample(parsed.help());
        finding.setIsBlocking(false);
        finding.setEnforcementMode("COMMENT");
        finding.setPolicyReason("sarif_import");
        finding.setIssueType(parsed.ruleId());
        finding.setRelatedFiles("");
        finding.setBlockingCandidate(false);
        finding.setVerificationStatus("NOT_REQUIRED");
        finding.setDetectorVersion("sarif-2.1.0");
        finding.setRuleConfigVersion(1L);
        finding.setPromptVersion("not-applicable");
        finding.setContextVersion("not-applicable");
        finding.setSchemaVersion("sarif-2.1.0");
        finding.setVerifierVersion("not-applicable");
        finding.setAggregationVersion("sarif-import-v1");
        finding.setPolicyVersion(1L);
        finding.setOriginalSeverity(parsed.severity());
        finding.setOriginalConfidence("HIGH");
        finding.setOriginalIsBlocking(false);
        finding.setDowngradeReason("");
        finding.setBlockReason("");
        finding.setAnchorType("ADDED_LINE");
        finding.setReviewDimension("SECURITY");
        finding.setFindingFingerprint(ReviewFindingIdentity.fingerprint(taskId, finding));
        finding.setComparisonStatus("UNMATCHED");
        finding.setComparisonConfidence(BigDecimal.ZERO);
        finding.setComparisonReason("PENDING_COMPARISON");
        finding.setComparisonVersion(ReviewFindingIdentity.VERSION);
        return finding;
    }
    private Map<String, String> ruleHelp(JsonNode rules) {
        Map<String, String> help = new LinkedHashMap<>();
        if (!rules.isArray()) {
            return help;
        }
        for (JsonNode rule : rules) {
            String id = rule.path("id").asText("").trim();
            if (!StringUtils.hasText(id) || id.length() > MAX_RULE_ID_LENGTH) {
                continue;
            }
            String value = rule.path("help").path("text").asText("");
            if (!StringUtils.hasText(value)) {
                value = rule.path("shortDescription").path("text").asText("");
            }
            help.put(id, value.trim());
        }
        return help;
    }
    private String sanitizePath(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim().replace('\\', '/');
        if (normalized.startsWith("/") || normalized.matches("^[A-Za-z]:/.*")
            || normalized.startsWith("file:") || normalized.contains("..")) {
            return "";
        }
        while (normalized.startsWith("./")) {
            normalized = normalized.substring(2);
        }
        return normalized.length() > 1024 ? "" : normalized;
    }
    private String severity(String level) {
        return switch (level == null ? "" : level.trim().toLowerCase()) {
            case "error" -> "HIGH";
            case "warning" -> "MEDIUM";
            case "note" -> "LOW";
            default -> "INFO";
        };
    }
    private String text(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private ReviewTask requireTask(Long taskId) {
        ReviewTask task = taskId == null || taskId < 1 ? null : reviewTaskMapper.selectById(taskId);
        if (task == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + taskId);
        }
        return task;
    }
    private ReviewExecutionAttempt requireCurrentAttempt(Long taskId, ReviewTask task) {
        Long attemptId = task.getCurrentAttemptId();
        if (attemptId == null || attemptId < 1) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "SARIF import requires a current review execution attempt"
            );
        }
        ReviewExecutionAttempt attempt = reviewExecutionAttemptMapper.selectById(attemptId);
        if (attempt == null || !Objects.equals(taskId, attempt.getTaskId())) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "The task current review execution attempt is missing or mismatched"
            );
        }
        return attempt;
    }
    private String resolveCommitSha(ReviewTask task, ReviewExecutionAttempt attempt) {
        String commitSha = text(attempt.getCommitSha(), task.getCommitSha());
        if (!StringUtils.hasText(commitSha)) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "SARIF import requires the attempt commit SHA");
        }
        if (commitSha.length() > 64 || commitSha.contains(" ") || commitSha.contains("\t")) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "The attempt commit SHA is invalid");
        }
        return commitSha;
    }
    private SarifImportBatchRow findBatch(Long taskId, Long attemptId, String tool, String version, String commit, String fingerprint) {
        return reviewFindingMapper.selectSarifImportBatch(taskId, attemptId, tool, version, commit, fingerprint);
    }
    private SarifImportResponse responseFromBatch(Long taskId, SarifImportBatchRow batch) {
        List<ReviewFinding> findings = batch.getId() == null ? List.of() : reviewFindingMapper.selectList(
            new LambdaQueryWrapper<ReviewFinding>().eq(ReviewFinding::getSourceBatchId, batch.getId()).orderByAsc(ReviewFinding::getId));
        List<SarifImportedFindingDto> imported = (findings == null ? List.<ReviewFinding>of() : findings).stream()
            .map(SarifFindingService::toImportedDto).toList();
        return new SarifImportResponse(taskId, batch.getImportedCount() == null ? imported.size() : batch.getImportedCount(),
            batch.getSkippedCount() == null ? 0 : batch.getSkippedCount(), imported);
    }
    private void supersedePreviousBatches(Long taskId, Long attemptId, ToolMetadata tool, String commit, String fingerprint) {
        List<SarifImportBatchRow> previous = reviewFindingMapper.selectActiveSarifImportBatches(
            taskId, attemptId, tool.name(), tool.version(), commit, fingerprint);
        if (previous != null) for (SarifImportBatchRow old : previous) {
            if (old.getId() == null) continue;
            reviewFindingMapper.update(null, new LambdaUpdateWrapper<ReviewFinding>()
                .eq(ReviewFinding::getTaskId, taskId).eq(ReviewFinding::getAttemptId, attemptId)
                .eq(ReviewFinding::getSourceBatchId, old.getId()).eq(ReviewFinding::getCurrentAttempt, true)
                .set(ReviewFinding::getCurrentAttempt, false));
            reviewFindingMapper.markSarifImportBatchSuperseded(old.getId(), java.time.LocalDateTime.now());
        }
        reviewFindingMapper.update(null, new LambdaUpdateWrapper<ReviewFinding>()
            .eq(ReviewFinding::getTaskId, taskId).eq(ReviewFinding::getAttemptId, attemptId)
            .eq(ReviewFinding::getSource, "SARIF").isNull(ReviewFinding::getSourceBatchId)
            .eq(ReviewFinding::getCurrentAttempt, true).set(ReviewFinding::getCurrentAttempt, false));
    }
    private ToolMetadata toolMetadata(JsonNode runs) {
        Set<String> names = new TreeSet<>();
        Set<String> versions = new TreeSet<>();
        for (JsonNode run : runs) {
            JsonNode driver = run.path("tool").path("driver");
            String name = metadataValue(driver.path("name").asText(""), 128);
            String version = metadataValue(driver.path("version").asText(""), 64);
            if (StringUtils.hasText(name)) {
                names.add(name);
            }
            if (StringUtils.hasText(version)) {
                versions.add(version);
            }
        }
        return new ToolMetadata(
            canonicalMetadata(names, "unknown", 128),
            canonicalMetadata(versions, "", 64)
        );
    }
    private String canonicalMetadata(Set<String> values, String fallback, int maxLength) {
        if (values.isEmpty()) {
            return fallback;
        }
        String normalized = String.join(",", values);
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
    }
    private String metadataValue(String value, int maxLength) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        String normalized = value.trim().replaceAll("[\\p{Cntrl}]", "");
        return normalized.length() > maxLength ? normalized.substring(0, maxLength) : normalized;
    }
    private String sha256(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(content.getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }
    private static SarifImportedFindingDto toImportedDto(ReviewFinding finding) {
        return new SarifImportedFindingDto(
            finding.getRuleId(),
            finding.getFilePath(),
            finding.getLineNumber(),
            finding.getSeverity(),
            finding.getMessage()
        );
    }
    private record ToolMetadata(String name, String version) {
    }
    private record ParsedResult(
        String ruleId,
        String filePath,
        int lineNumber,
        String severity,
        String message,
        String help
    ) {
    }
}
