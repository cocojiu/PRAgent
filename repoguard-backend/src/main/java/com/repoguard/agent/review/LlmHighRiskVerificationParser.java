package com.repoguard.agent.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Locale;
import java.util.Objects;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
class LlmHighRiskVerificationParser {

    private final ObjectMapper objectMapper;
    private final LlmReviewJsonExtractor jsonExtractor;

    LlmHighRiskVerificationParser(ObjectMapper objectMapper, LlmReviewJsonExtractor jsonExtractor) {
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.jsonExtractor = Objects.requireNonNull(jsonExtractor, "jsonExtractor");
    }

    LlmHighRiskVerificationDecision parse(String content) {
        try {
            JsonNode root = objectMapper.readTree(jsonExtractor.extractJsonObject(content));
            if (!root.isObject()) {
                throw new IllegalArgumentException("Verification response root must be an object");
            }
            String schemaVersion = requiredText(root, "schemaVersion", "schema_version");
            if (!LlmReviewVersions.VERIFIER.equals(schemaVersion)) {
                throw new IllegalArgumentException("Unsupported verification schema version");
            }
            return new LlmHighRiskVerificationDecision(
                verdict(requiredText(root, "verdict")),
                requiredBoolean(root, "evidenceSupported", "evidence_supported"),
                requiredBoolean(root, "preconditionsSatisfied", "preconditions_satisfied"),
                requiredBoolean(root, "addedLineValid", "added_line_valid"),
                requiredBoolean(root, "protectionPresent", "protection_present"),
                requiredText(root, "existingProtection", "existing_protection"),
                confidence(requiredText(root, "confidence")),
                requiredText(root, "reason")
            );
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Unable to parse high-risk verification response", ex);
        }
    }

    private LlmHighRiskVerificationDecision.Verdict verdict(String value) {
        try {
            return LlmHighRiskVerificationDecision.Verdict.valueOf(value.toUpperCase(Locale.ROOT));
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Invalid high-risk verification verdict", ex);
        }
    }

    private String confidence(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!java.util.Set.of("LOW", "MEDIUM", "HIGH").contains(normalized)) {
            throw new IllegalArgumentException("Invalid high-risk verification confidence");
        }
        return normalized;
    }

    private String requiredText(JsonNode node, String... fields) {
        String value = text(node, fields);
        if (!StringUtils.hasText(value)) {
            throw new IllegalArgumentException("Missing high-risk verification field: " + fields[0]);
        }
        return value;
    }

    private String text(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isTextual() && StringUtils.hasText(value.asText())) {
                return value.asText().trim();
            }
        }
        return "";
    }

    private boolean requiredBoolean(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isBoolean()) {
                return value.asBoolean();
            }
            if (!value.isMissingNode() && !value.isNull()) {
                throw new IllegalArgumentException("Invalid high-risk verification boolean: " + fields[0]);
            }
        }
        throw new IllegalArgumentException("Missing high-risk verification field: " + fields[0]);
    }
}
