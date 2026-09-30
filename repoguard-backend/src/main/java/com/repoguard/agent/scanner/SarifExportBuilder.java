package com.repoguard.agent.scanner;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.SarifExportDto;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import com.repoguard.agent.mapper.ReviewFindingMapper.SarifImportBatchRow;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.util.StringUtils;

/** Builds a bounded SARIF document grouped by scanner name and version. */
final class SarifExportBuilder {
    private static final String SARIF_VERSION = "2.1.0";
    private static final String SARIF_SCHEMA = "https://json.schemastore.org/sarif-2.1.0.json";

    private SarifExportBuilder() {
    }

    static SarifExportDto export(Long taskId, ReviewFindingMapper mapper, ObjectMapper json, int maxFindings, int maxBytes) {
        validateLimits(maxFindings, maxBytes);
        if (mapper.selectSarifExportTextBytes(taskId, maxFindings + 1) > maxBytes) {
            throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "SARIF export text exceeds " + maxBytes + " bytes");
        }
        List<ReviewFinding> findings = mapper.selectList(new LambdaQueryWrapper<ReviewFinding>()
            .select(ReviewFinding::getId, ReviewFinding::getSource, ReviewFinding::getSourceBatchId,
                ReviewFinding::getRuleId, ReviewFinding::getSeverity, ReviewFinding::getFilePath,
                ReviewFinding::getLineNumber, ReviewFinding::getMessage)
            .eq(ReviewFinding::getTaskId, taskId)
            .eq(ReviewFinding::getCurrentAttempt, true)
            .eq(ReviewFinding::getCategory, "FINDING")
            .orderByAsc(ReviewFinding::getId)
            .last("limit " + (maxFindings + 1)));
        if (findings != null && findings.size() > maxFindings) {
            throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "SARIF export exceeds " + maxFindings + " findings");
        }
        Map<String, ExportRun> runs = new LinkedHashMap<>();
        Map<Long, SarifImportBatchRow> batches = new LinkedHashMap<>();
        for (ReviewFinding finding : findings == null ? List.<ReviewFinding>of() : findings) {
            String ruleId = text(finding.getRuleId(), "SARIF-" + finding.getId());
            SarifImportBatchRow batch = sarifBatch(finding, mapper, batches);
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
        SarifExportDto document = new SarifExportDto(SARIF_VERSION, SARIF_SCHEMA,
            runs.values().stream().map(ExportRun::toSarifRun).toList());
        verifyDocumentBytes(document, json, maxBytes);
        return document;
    }

    static void validateLimits(int maxFindings, int maxBytes) {
        if (maxFindings < 1 || maxFindings > 100_000 || maxBytes < 1_024 || maxBytes > 32 * 1024 * 1024) {
            throw new IllegalArgumentException("SARIF export limits require 1..100000 findings and 1024..33554432 bytes");
        }
    }

    private static void verifyDocumentBytes(SarifExportDto document, ObjectMapper json, int maxBytes) {
        ByteBudgetOutput output = new ByteBudgetOutput(maxBytes);
        try {
            json.writeValue(output, document);
        } catch (IOException ex) {
            if (output.exceeded) {
                throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE, "SARIF export exceeds " + maxBytes + " UTF-8 document bytes");
            }
            throw new IllegalStateException("Unable to serialize SARIF export", ex);
        }
    }

    private static SarifImportBatchRow sarifBatch(ReviewFinding finding, ReviewFindingMapper mapper, Map<Long, SarifImportBatchRow> batches) {
        if (finding == null || finding.getSourceBatchId() == null
            || !"SARIF".equalsIgnoreCase(text(finding.getSource(), ""))) {
            return null;
        }
        Long batchId = finding.getSourceBatchId();
        if (!batches.containsKey(batchId)) {
            batches.put(batchId, mapper.selectSarifImportBatchById(batchId));
        }
        return batches.get(batchId);
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

    private static final class ByteBudgetOutput extends OutputStream {
        private final int limit;
        private int written;
        private boolean exceeded;

        private ByteBudgetOutput(int limit) {
            this.limit = limit;
        }

        @Override
        public void write(int value) throws IOException {
            reserve(1);
        }

        @Override
        public void write(byte[] data, int offset, int length) throws IOException {
            java.util.Objects.checkFromIndexSize(offset, length, data.length);
            reserve(length);
        }

        private void reserve(int length) throws IOException {
            if (length > limit - written) {
                exceeded = true;
                throw new IOException("SARIF document byte budget exceeded");
            }
            written += length;
        }
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
