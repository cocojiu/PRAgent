package com.repoguard.agent.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.util.StringUtils;

public class LlmReviewResultParser {

    private static final Set<String> RISK_LEVELS = Set.of("INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Set<String> SEVERITY_LEVELS = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Set<String> CONFIDENCE_LEVELS = Set.of("LOW", "MEDIUM", "HIGH");
    private static final String DEFAULT_MESSAGE = "LLM review reported a potential issue.";
    private static final String DEFAULT_RECOMMENDATION = "Review the surrounding context and apply a targeted fix.";

    private final ObjectMapper objectMapper;
    private final LlmReviewJsonExtractor jsonExtractor;

    public LlmReviewResultParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.jsonExtractor = new LlmReviewJsonExtractor();
    }

    public ReviewResult parse(String content) {
        try {
            JsonNode root = objectMapper.readTree(jsonExtractor.extractJsonObject(content));
            ObjectNode repairedRoot = repairAndValidateRoot(root);
            String riskLevel = repairedRoot.path("riskLevel").asText();
            List<ReviewFindingResult> findings = new ArrayList<>();
            for (JsonNode finding : repairedRoot.path("findings")) {
                findings.add(new ReviewFindingResult(
                    finding.path("severity").asText(),
                    "LLM",
                    null,
                    finding.path("filePath").asText(),
                    readLineNumber(finding),
                    finding.path("message").asText(),
                    finding.path("recommendation").asText(),
                    finding.path("confidence").asText(),
                    finding.path("evidence").asText(""),
                    finding.path("impact").asText(""),
                    finding.path("fixExample").asText(finding.path("recommendation").asText()),
                    finding.path("isBlocking").asBoolean(false),
                    finding.path("reviewDimension").asText("LLM")
                ));
            }
            return ReviewResult.completed(riskLevel, findings);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to parse LLM review result: " + failureSummary(content, ex), ex);
        }
    }

    private ObjectNode repairAndValidateRoot(JsonNode root) {
        if (!root.isObject()) {
            throw new IllegalArgumentException("LLM review schema root must be a JSON object");
        }

        ObjectNode repaired = objectMapper.createObjectNode();
        repaired.put("riskLevel", normalizeEnum(readText(root, "riskLevel", "risk_level", "risk"), RISK_LEVELS, "INFO"));
        repaired.set("findings", repairFindings(root.path("findings")));
        validateRootSchema(repaired);
        return repaired;
    }

    private ArrayNode repairFindings(JsonNode findingsNode) {
        ArrayNode repairedFindings = objectMapper.createArrayNode();
        if (findingsNode == null || findingsNode.isMissingNode() || findingsNode.isNull()) {
            return repairedFindings;
        }
        if (findingsNode.isObject()) {
            ObjectNode finding = repairFinding(findingsNode);
            if (finding != null) {
                repairedFindings.add(finding);
            }
            return repairedFindings;
        }
        if (!findingsNode.isArray()) {
            return repairedFindings;
        }
        for (JsonNode findingNode : findingsNode) {
            ObjectNode finding = repairFinding(findingNode);
            if (finding != null) {
                repairedFindings.add(finding);
            }
        }
        return repairedFindings;
    }

    private ObjectNode repairFinding(JsonNode finding) {
        if (!finding.isObject()) {
            return null;
        }

        ObjectNode repaired = objectMapper.createObjectNode();
        String severity = normalizeEnum(readText(finding, "severity", "level"), SEVERITY_LEVELS, "LOW");
        String confidence = readText(finding, "confidence");
        String recommendation = defaultText(readText(finding, "recommendation", "suggestion", "fix"), DEFAULT_RECOMMENDATION);

        repaired.put("severity", severity);
        repaired.put("filePath", defaultText(readText(finding, "filePath", "file", "path"), "unknown"));
        Integer lineNumber = readLineNumber(finding);
        if (lineNumber == null || lineNumber < 1) {
            repaired.putNull("lineNumber");
        } else {
            repaired.put("lineNumber", lineNumber);
        }
        repaired.put("message", defaultText(readText(finding, "message", "description", "issue"), DEFAULT_MESSAGE));
        repaired.put("recommendation", recommendation);
        repaired.put("confidence", normalizeEnum(confidence, CONFIDENCE_LEVELS, StringUtils.hasText(confidence) ? defaultConfidence(severity) : "MEDIUM"));
        repaired.put("evidence", defaultText(readText(finding, "evidence"), ""));
        repaired.put("impact", defaultText(readText(finding, "impact"), ""));
        repaired.put("fixExample", defaultText(readText(finding, "fixExample", "fix_example"), recommendation));
        repaired.put("isBlocking", readBoolean(finding, "isBlocking", "is_blocking", "blocking") || defaultBlocking(severity));
        repaired.put("reviewDimension", defaultText(readText(finding, "reviewDimension", "review_dimension"), "LLM"));
        validateFindingSchema(repaired);
        return repaired;
    }

    private void validateRootSchema(ObjectNode root) {
        if (!RISK_LEVELS.contains(root.path("riskLevel").asText())) {
            throw new IllegalArgumentException("LLM review schema riskLevel is invalid");
        }
        if (!root.path("findings").isArray()) {
            throw new IllegalArgumentException("LLM review schema findings must be an array");
        }
    }

    private void validateFindingSchema(ObjectNode finding) {
        requireEnum(finding, "severity", SEVERITY_LEVELS);
        requireText(finding, "filePath");
        requireText(finding, "message");
        requireText(finding, "recommendation");
        requireEnum(finding, "confidence", CONFIDENCE_LEVELS);
        requireText(finding, "reviewDimension");
    }

    private void requireText(ObjectNode node, String field) {
        if (!StringUtils.hasText(node.path(field).asText())) {
            throw new IllegalArgumentException("LLM review schema field is blank: " + field);
        }
    }

    private void requireEnum(ObjectNode node, String field, Set<String> allowedValues) {
        String value = node.path(field).asText();
        if (!allowedValues.contains(value)) {
            throw new IllegalArgumentException("LLM review schema enum is invalid: " + field);
        }
    }

    private String normalizeEnum(String value, Set<String> allowedValues, String fallback) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        return allowedValues.contains(normalized) ? normalized : fallback;
    }

    private String defaultConfidence(String severity) {
        return switch (severity) {
            case "CRITICAL", "HIGH" -> "HIGH";
            case "MEDIUM" -> "MEDIUM";
            default -> "LOW";
        };
    }

    private boolean defaultBlocking(String severity) {
        return "CRITICAL".equals(severity) || "HIGH".equals(severity);
    }

    private String readText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isTextual() && StringUtils.hasText(value.asText())) {
                return value.asText().trim();
            }
            if (value.isNumber() || value.isBoolean()) {
                return value.asText();
            }
        }
        return "";
    }

    private String defaultText(String value, String defaultValue) {
        return StringUtils.hasText(value) ? value : defaultValue;
    }

    private Integer readLineNumber(JsonNode finding) {
        for (String field : List.of("lineNumber", "line", "line_number")) {
            JsonNode value = finding.path(field);
            if (value.isNumber()) {
                return value.asInt();
            }
            if (value.isTextual()) {
                try {
                    return Integer.parseInt(value.asText().trim());
                } catch (NumberFormatException ignored) {
                    // Try the next supported field name.
                }
            }
        }
        return null;
    }

    private boolean readBoolean(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isBoolean()) {
                return value.asBoolean();
            }
            if (value.isTextual() && StringUtils.hasText(value.asText())) {
                return Boolean.parseBoolean(value.asText().trim());
            }
        }
        return false;
    }

    private String failureSummary(String content, Exception ex) {
        int length = content == null ? 0 : content.length();
        String reason = ex.getMessage();
        if (!StringUtils.hasText(reason)) {
            reason = ex.getClass().getSimpleName();
        }
        return "length=" + length + ", reason=" + reason.replaceAll("\\s+", " ").trim();
    }
}
