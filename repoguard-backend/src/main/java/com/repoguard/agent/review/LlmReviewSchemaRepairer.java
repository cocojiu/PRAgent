package com.repoguard.agent.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class LlmReviewSchemaRepairer {

    private static final Set<String> RISK_LEVELS = Set.of("INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Set<String> SEVERITY_LEVELS = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Set<String> CONFIDENCE_LEVELS = Set.of("LOW", "MEDIUM", "HIGH");
    private static final String DEFAULT_MESSAGE = "LLM review reported a potential issue.";
    private static final String DEFAULT_RECOMMENDATION = "Review the surrounding context and apply a targeted fix.";

    private final ObjectMapper objectMapper;

    public LlmReviewSchemaRepairer(ObjectMapper objectMapper) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
    }

    ObjectNode repairAndValidateRoot(JsonNode root) {
        if (!root.isObject()) {
            throw new IllegalArgumentException("LLM review schema root must be a JSON object");
        }

        ObjectNode repaired = objectMapper.createObjectNode();
        String schemaVersion = readText(root, "schemaVersion", "schema_version");
        repaired.put(
            "schemaVersion",
            StringUtils.hasText(schemaVersion) ? schemaVersion : "review-schema-v1-repaired"
        );
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

        repaired.put("issueType", defaultText(readText(finding, "issueType", "issue_type", "category"), "GENERAL"));
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
        repaired.put("confidence", normalizeEnum(confidence, CONFIDENCE_LEVELS, "MEDIUM"));
        repaired.put("evidence", defaultText(readText(finding, "evidence"), ""));
        repaired.put("preconditions", defaultText(readText(finding, "preconditions", "precondition"), ""));
        repaired.put("impact", defaultText(readText(finding, "impact"), ""));
        repaired.put("fixExample", defaultText(readText(finding, "fixExample", "fix_example"), recommendation));
        repaired.set("relatedFiles", readRelatedFiles(finding));
        repaired.put("blockingCandidate", readBoolean(finding, "blockingCandidate", "blocking_candidate"));
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
        requireText(finding, "issueType");
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

    private ArrayNode readRelatedFiles(JsonNode finding) {
        ArrayNode related = objectMapper.createArrayNode();
        JsonNode source = finding.path("relatedFiles");
        if (!source.isArray()) {
            source = finding.path("related_files");
        }
        if (!source.isArray()) {
            return related;
        }
        for (JsonNode value : source) {
            if (value.isTextual() && StringUtils.hasText(value.asText())) {
                related.add(value.asText().trim());
            }
        }
        return related;
    }
}
