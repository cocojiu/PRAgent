package com.repoguard.agent.review;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/** JSON Schema documents sent to providers that support strict structured output. */
public final class LlmStructuredOutputSchemas {

    public static final String REVIEW_SCHEMA_NAME = "repoguard_review_v2";
    public static final String VERIFICATION_SCHEMA_NAME = "repoguard_verification_v1";

    private LlmStructuredOutputSchemas() {
    }

    public static Map<String, Object> review() {
        Map<String, Object> finding = map(
            "type", "object",
            "additionalProperties", false,
            "properties", map(
                "issueType", map("type", "string"),
                "severity", map("type", "string", "enum", List.of("LOW", "MEDIUM", "HIGH", "CRITICAL")),
                "confidence", map("type", "string", "enum", List.of("LOW", "MEDIUM", "HIGH")),
                "filePath", map("type", "string"),
                "lineNumber", map("type", List.of("integer", "null"), "minimum", 1),
                "relatedFiles", map("type", "array", "items", map("type", "string")),
                "message", map("type", "string"),
                "evidence", map("type", "string"),
                "preconditions", map("type", "string"),
                "impact", map("type", "string"),
                "recommendation", map("type", "string"),
                "fixExample", map("type", "string"),
                "reviewDimension", map("type", "string"),
                "blockingCandidate", map("type", "boolean")
            ),
            "required", List.of(
                "issueType", "severity", "confidence", "filePath", "lineNumber", "relatedFiles",
                "message", "evidence", "preconditions", "impact", "recommendation", "reviewDimension",
                "blockingCandidate", "fixExample"
            )
        );
        return map(
            "type", "object",
            "additionalProperties", false,
            "properties", map(
                "schemaVersion", map("type", "string"),
                "riskLevel", map("type", "string", "enum", List.of("INFO", "LOW", "MEDIUM", "HIGH", "CRITICAL")),
                "findings", map("type", "array", "items", finding)
            ),
            "required", List.of("schemaVersion", "riskLevel", "findings")
        );
    }

    public static Map<String, Object> verification() {
        return map(
            "type", "object",
            "additionalProperties", false,
            "properties", map(
                "schemaVersion", map("type", "string"),
                "verdict", map("type", "string", "enum", List.of("VERIFIED", "REJECTED", "UNCERTAIN")),
                "evidenceSupported", map("type", "boolean"),
                "preconditionsSatisfied", map("type", "boolean"),
                "addedLineValid", map("type", "boolean"),
                "protectionPresent", map("type", "boolean"),
                "existingProtection", map("type", "string"),
                "confidence", map("type", "string", "enum", List.of("LOW", "MEDIUM", "HIGH")),
                "reason", map("type", "string")
            ),
            "required", List.of(
                "schemaVersion", "verdict", "evidenceSupported", "preconditionsSatisfied", "addedLineValid",
                "protectionPresent", "existingProtection", "confidence", "reason"
            )
        );
    }

    private static Map<String, Object> map(Object... entries) {
        if (entries.length % 2 != 0) {
            throw new IllegalArgumentException("Schema map entries must be key/value pairs");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < entries.length; i += 2) {
            result.put(String.valueOf(entries[i]), entries[i + 1]);
        }
        return result;
    }
}
