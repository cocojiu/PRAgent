package com.repoguard.agent.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.util.StringUtils;

public class LlmReviewResultParser {

    private final ObjectMapper objectMapper;

    public LlmReviewResultParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ReviewResult parse(String content) {
        try {
            JsonNode root = objectMapper.readTree(extractJsonObject(content));
            String riskLevel = defaultText(readText(root, "riskLevel", "risk_level", "risk"), "INFO").toUpperCase();
            List<ReviewFindingResult> findings = new ArrayList<>();
            JsonNode findingNodes = root.path("findings");
            if (!findingNodes.isArray()) {
                findingNodes = objectMapper.createArrayNode();
            }
            for (JsonNode finding : findingNodes) {
                findings.add(new ReviewFindingResult(
                    defaultText(readText(finding, "severity", "level"), "LOW").toUpperCase(),
                    "LLM",
                    null,
                    defaultText(readText(finding, "filePath", "file", "path"), "unknown"),
                    readLineNumber(finding),
                    defaultText(readText(finding, "message", "description", "issue"), "LLM 审查发现潜在问题"),
                    defaultText(readText(finding, "recommendation", "suggestion", "fix"), "请结合上下文确认并修复。")
                ));
            }
            return ReviewResult.completed(riskLevel, findings);
        } catch (Exception ex) {
            throw new IllegalStateException("Unable to parse LLM review result: " + failureSummary(content, ex), ex);
        }
    }

    private String extractJsonObject(String content) {
        String trimmed = stripJsonFence(content);
        int start = trimmed.indexOf('{');
        if (start < 0) {
            throw new IllegalArgumentException("LLM result does not contain a JSON object");
        }

        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = start; i < trimmed.length(); i++) {
            char current = trimmed.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\' && inString) {
                escaped = true;
                continue;
            }
            if (current == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (current == '{') {
                depth++;
            } else if (current == '}') {
                depth--;
                if (depth == 0) {
                    return trimmed.substring(start, i + 1);
                }
            }
        }
        throw new IllegalArgumentException("LLM result contains an incomplete JSON object");
    }

    private String stripJsonFence(String content) {
        String trimmed = content == null ? "" : content.trim();
        if (trimmed.startsWith("```")) {
            trimmed = trimmed.replaceFirst("^```\\s*(?:json)?\\s*", "").replaceFirst("\\s*```$", "").trim();
        }
        return trimmed;
    }

    private String readText(JsonNode node, String... fields) {
        for (String field : fields) {
            JsonNode value = node.path(field);
            if (value.isTextual() && StringUtils.hasText(value.asText())) {
                return value.asText().trim();
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

    private String failureSummary(String content, Exception ex) {
        int length = content == null ? 0 : content.length();
        String reason = ex.getMessage();
        if (!StringUtils.hasText(reason)) {
            reason = ex.getClass().getSimpleName();
        }
        return "length=" + length + ", reason=" + reason.replaceAll("\\s+", " ").trim();
    }
}
