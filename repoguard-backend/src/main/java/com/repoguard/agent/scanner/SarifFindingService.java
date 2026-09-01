package com.repoguard.agent.scanner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.SarifExportDto;
import com.repoguard.agent.dto.SarifImportRequest;
import com.repoguard.agent.dto.SarifImportResponse;
import com.repoguard.agent.dto.SarifImportedFindingDto;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewTaskMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;

@Service
public class SarifFindingService {

    private static final String SARIF_VERSION = "2.1.0";
    private static final String SARIF_SCHEMA = "https://json.schemastore.org/sarif-2.1.0.json";
    private static final int MAX_RUNS = 20;
    private static final int MAX_RESULTS = 5_000;
    private static final int MAX_RULE_ID_LENGTH = 64;

    private final ObjectMapper objectMapper;
    private final ReviewTaskMapper reviewTaskMapper;
    private final ReviewFindingMapper reviewFindingMapper;

    public SarifFindingService(
        ObjectMapper objectMapper,
        ReviewTaskMapper reviewTaskMapper,
        ReviewFindingMapper reviewFindingMapper
    ) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.reviewTaskMapper = Objects.requireNonNull(reviewTaskMapper, "reviewTaskMapper");
        this.reviewFindingMapper = Objects.requireNonNull(reviewFindingMapper, "reviewFindingMapper");
    }

    @Transactional
    public SarifImportResponse importFindings(Long taskId, SarifImportRequest request) {
        requireTask(taskId);
        if (request == null || !StringUtils.hasText(request.content())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "SARIF content is required");
        }
        JsonNode root = parse(request.content());
        if (!SARIF_VERSION.equals(root.path("version").asText())) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Only SARIF version 2.1.0 is supported");
        }
        JsonNode runs = root.path("runs");
        if (!runs.isArray() || runs.isEmpty() || runs.size() > MAX_RUNS) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "SARIF runs must be a non-empty array of at most 20 items");
        }
        reviewFindingMapper.markCurrentAttemptHistorical(taskId);
        List<SarifImportedFindingDto> imported = new ArrayList<>();
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
                reviewFindingMapper.insert(toEntity(taskId, parsed));
                imported.add(new SarifImportedFindingDto(
                    parsed.ruleId(), parsed.filePath(), parsed.lineNumber(), parsed.severity(), parsed.message()
                ));
            }
        }
        return new SarifImportResponse(taskId, imported.size(), skipped, List.copyOf(imported));
    }

    public SarifExportDto exportFindings(Long taskId) {
        requireTask(taskId);
        List<ReviewFinding> findings = reviewFindingMapper.selectList(
            new LambdaQueryWrapper<ReviewFinding>()
                .eq(ReviewFinding::getTaskId, taskId)
                .eq(ReviewFinding::getCurrentAttempt, true)
                .eq(ReviewFinding::getCategory, "FINDING")
                .orderByAsc(ReviewFinding::getId)
        );
        Map<String, Map<String, Object>> rules = new LinkedHashMap<>();
        List<Map<String, Object>> results = new ArrayList<>();
        for (ReviewFinding finding : findings) {
            String ruleId = text(finding.getRuleId(), "SARIF-" + finding.getId());
            rules.putIfAbsent(ruleId, Map.of("id", ruleId, "name", ruleId));
            Map<String, Object> region = new LinkedHashMap<>();
            if (finding.getLineNumber() != null && finding.getLineNumber() > 0) {
                region.put("startLine", finding.getLineNumber());
            }
            Map<String, Object> physical = new LinkedHashMap<>();
            physical.put("artifactLocation", Map.of("uri", finding.getFilePath()));
            physical.put("region", region);
            results.add(Map.of(
                "ruleId", ruleId,
                "level", sarifLevel(finding.getSeverity()),
                "message", Map.of("text", text(finding.getMessage(), "RepoGuard finding")),
                "locations", List.of(Map.of("physicalLocation", physical))
            ));
        }
        Map<String, Object> driver = new LinkedHashMap<>();
        driver.put("name", "RepoGuard Agent");
        driver.put("version", "1");
        driver.put("rules", List.copyOf(rules.values()));
        Map<String, Object> tool = Map.of("driver", driver);
        return new SarifExportDto(SARIF_VERSION, SARIF_SCHEMA, List.of(
            Map.of("tool", tool, "results", List.copyOf(results))
        ));
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

    private ReviewFinding toEntity(Long taskId, ParsedResult parsed) {
        ReviewFinding finding = new ReviewFinding();
        finding.setTaskId(taskId);
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

    private String sarifLevel(String severity) {
        return switch (severity == null ? "" : severity.toUpperCase()) {
            case "CRITICAL", "HIGH" -> "error";
            case "MEDIUM" -> "warning";
            default -> "note";
        };
    }

    private String text(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private void requireTask(Long taskId) {
        if (taskId == null || taskId < 1 || reviewTaskMapper.selectById(taskId) == null) {
            throw new BusinessException(ErrorCode.TASK_NOT_FOUND, "Review task not found: " + taskId);
        }
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
