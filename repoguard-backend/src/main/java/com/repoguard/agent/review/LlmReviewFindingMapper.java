package com.repoguard.agent.review;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

@Component
public class LlmReviewFindingMapper {

    List<ReviewFindingResult> mapFindings(JsonNode findingsNode) {
        List<ReviewFindingResult> findings = new ArrayList<>();
        if (findingsNode == null || !findingsNode.isArray()) {
            return findings;
        }
        for (JsonNode finding : findingsNode) {
            findings.add(mapFinding(finding));
        }
        return findings;
    }

    private ReviewFindingResult mapFinding(JsonNode finding) {
        return new ReviewFindingResult(
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
            false,
            finding.path("reviewDimension").asText("LLM"),
            EnforcementMode.OBSERVE.name(),
            "llm_candidate_unscored",
            finding.path("issueType").asText("GENERAL"),
            finding.path("preconditions").asText(""),
            mapRelatedFiles(finding.path("relatedFiles")),
            finding.path("blockingCandidate").asBoolean(false),
            LlmVerificationStatus.NOT_REQUIRED.name()
        );
    }

    private List<String> mapRelatedFiles(JsonNode relatedFiles) {
        if (relatedFiles == null || !relatedFiles.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        relatedFiles.forEach(value -> {
            if (value.isTextual() && !value.asText().isBlank()) {
                values.add(value.asText().trim());
            }
        });
        return List.copyOf(values);
    }

    private Integer readLineNumber(JsonNode finding) {
        JsonNode value = finding.path("lineNumber");
        if (value.isNumber()) {
            return value.asInt();
        }
        return null;
    }
}
