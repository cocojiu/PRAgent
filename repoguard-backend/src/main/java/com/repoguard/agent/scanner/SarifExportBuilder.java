package com.repoguard.agent.scanner;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.dto.SarifExportDto;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper.SarifImportBatchRow;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

/** Builds a bounded SARIF 2.1.0 document while retaining each scanner batch identity. */
final class SarifExportBuilder {
    private static final String SARIF_VERSION = "2.1.0";
    private static final String SARIF_SCHEMA = "https://json.schemastore.org/sarif-2.1.0.json";

    private SarifExportBuilder() {
    }

    static SarifExportDto export(Long taskId, ReviewFindingMapper mapper) {
        List<ReviewFinding> findings = mapper.selectList(new LambdaQueryWrapper<ReviewFinding>()
            .eq(ReviewFinding::getTaskId, taskId)
            .eq(ReviewFinding::getCurrentAttempt, true)
            .eq(ReviewFinding::getCategory, "FINDING")
            .orderByAsc(ReviewFinding::getId));
        Map<String, ExportRun> runs = new LinkedHashMap<>();
        for (ReviewFinding finding : findings == null ? List.<ReviewFinding>of() : findings) {
            String ruleId = text(finding.getRuleId(), "SARIF-" + finding.getId());
            SarifImportBatchRow batch = sarifBatch(finding, mapper);
            String toolName = batch == null ? "RepoGuard Agent" : text(batch.getToolName(), "unknown");
            String toolVersion = batch == null ? "1" : text(batch.getToolVersion(), "");
            ExportRun run = runs.computeIfAbsent(toolName + "\u0000" + toolVersion,
                ignored -> new ExportRun(toolName, toolVersion));
            run.rules.putIfAbsent(ruleId, Map.of("id", ruleId, "name", ruleId));
            Map<String, Object> region = new LinkedHashMap<>();
            if (finding.getLineNumber() != null && finding.getLineNumber() > 0) {
                region.put("startLine", finding.getLineNumber());
            }
            Map<String, Object> physical = new LinkedHashMap<>();
            physical.put("artifactLocation", Map.of("uri", finding.getFilePath()));
            physical.put("region", region);
            run.results.add(Map.of(
                "ruleId", ruleId,
                "level", sarifLevel(finding.getSeverity()),
                "message", Map.of("text", text(finding.getMessage(), "RepoGuard finding")),
                "locations", List.of(Map.of("physicalLocation", physical))));
        }
        return new SarifExportDto(SARIF_VERSION, SARIF_SCHEMA,
            runs.values().stream().map(ExportRun::toSarifRun).toList());
    }

    private static SarifImportBatchRow sarifBatch(ReviewFinding finding, ReviewFindingMapper mapper) {
        if (finding == null || finding.getSourceBatchId() == null
            || !"SARIF".equalsIgnoreCase(text(finding.getSource(), ""))) {
            return null;
        }
        return mapper.selectSarifImportBatchById(finding.getSourceBatchId());
    }

    private static String sarifLevel(String severity) {
        return switch (severity == null ? "" : severity.toUpperCase()) {
            case "CRITICAL", "HIGH" -> "error";
            case "MEDIUM" -> "warning";
            default -> "note";
        };
    }

    private static String text(String value, String fallback) {
        return StringUtils.hasText(value) ? value.trim() : fallback;
    }

    private static final class ExportRun {
        private final String toolName;
        private final String toolVersion;
        private final Map<String, Map<String, Object>> rules = new LinkedHashMap<>();
        private final List<Map<String, Object>> results = new ArrayList<>();

        private ExportRun(String toolName, String toolVersion) {
            this.toolName = toolName;
            this.toolVersion = toolVersion;
        }

        private Map<String, Object> toSarifRun() {
            Map<String, Object> driver = new LinkedHashMap<>();
            driver.put("name", toolName);
            driver.put("version", toolVersion);
            driver.put("rules", List.copyOf(rules.values()));
            return Map.of("tool", Map.of("driver", driver), "results", List.copyOf(results));
        }
    }
}
