package com.repoguard.agent.review;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.util.StringUtils;

public class LlmReviewResultParser {

    private final ObjectMapper objectMapper;
    private final LlmReviewJsonExtractor jsonExtractor;
    private final LlmReviewSchemaRepairer schemaRepairer;

    public LlmReviewResultParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.jsonExtractor = new LlmReviewJsonExtractor();
        this.schemaRepairer = new LlmReviewSchemaRepairer(objectMapper);
    }

    public ReviewResult parse(String content) {
        try {
            JsonNode root = objectMapper.readTree(jsonExtractor.extractJsonObject(content));
            ObjectNode repairedRoot = schemaRepairer.repairAndValidateRoot(root);
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

    private Integer readLineNumber(JsonNode finding) {
        JsonNode value = finding.path("lineNumber");
        if (value.isNumber()) {
            return value.asInt();
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
