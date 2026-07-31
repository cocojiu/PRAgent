package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class LlmReviewFindingMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LlmReviewFindingMapper mapper = new LlmReviewFindingMapper();

    @Test
    void mapsRepairedFindingFieldsToReviewFindingResult() throws Exception {
        List<ReviewFindingResult> findings = mapper.mapFindings(objectMapper.readTree("""
            [
              {
                "severity": "HIGH",
                "filePath": "src/AdminController.java",
                "lineNumber": 42,
                "message": "Missing authorization",
                "recommendation": "Add @RequireRole",
                "confidence": "HIGH",
                "evidence": "POST /admin",
                "issueType": "MISSING_AUTHORIZATION",
                "preconditions": "An unauthenticated caller reaches POST /admin",
                "impact": "Unauthorized write",
                "fixExample": "@RequireRole(\\"ADMIN\\")",
                "isBlocking": true,
                "blockingCandidate": true,
                "relatedFiles": ["src/SecurityConfig.java"],
                "reviewDimension": "ACCESS_CONTROL"
              }
            ]
            """));

        ReviewFindingResult finding = findings.getFirst();
        assertThat(finding.severity()).isEqualTo("HIGH");
        assertThat(finding.source()).isEqualTo("LLM");
        assertThat(finding.ruleId()).isNull();
        assertThat(finding.filePath()).isEqualTo("src/AdminController.java");
        assertThat(finding.lineNumber()).isEqualTo(42);
        assertThat(finding.message()).isEqualTo("Missing authorization");
        assertThat(finding.recommendation()).isEqualTo("Add @RequireRole");
        assertThat(finding.confidence()).isEqualTo("HIGH");
        assertThat(finding.evidence()).isEqualTo("POST /admin");
        assertThat(finding.impact()).isEqualTo("Unauthorized write");
        assertThat(finding.fixExample()).isEqualTo("@RequireRole(\"ADMIN\")");
        assertThat(finding.isBlocking()).isFalse();
        assertThat(finding.blockingCandidate()).isTrue();
        assertThat(finding.issueType()).isEqualTo("MISSING_AUTHORIZATION");
        assertThat(finding.preconditions()).contains("unauthenticated caller");
        assertThat(finding.relatedFiles()).containsExactly("src/SecurityConfig.java");
        assertThat(finding.enforcementMode()).isEqualTo("OBSERVE");
        assertThat(finding.reviewDimension()).isEqualTo("ACCESS_CONTROL");
    }

    @Test
    void usesRecommendationAsFixExampleWhenFixExampleIsMissing() throws Exception {
        ReviewFindingResult finding = mapper.mapFindings(objectMapper.readTree("""
            [
              {
                "severity": "LOW",
                "filePath": "src/App.vue",
                "message": "Missing empty state",
                "recommendation": "Add empty state",
                "confidence": "LOW",
                "reviewDimension": "LLM"
              }
            ]
            """)).getFirst();

        assertThat(finding.lineNumber()).isNull();
        assertThat(finding.fixExample()).isEqualTo("Add empty state");
        assertThat(finding.evidence()).isEmpty();
        assertThat(finding.impact()).isEmpty();
        assertThat(finding.isBlocking()).isFalse();
    }

    @Test
    void returnsEmptyListForNonArrayInput() throws Exception {
        assertThat(mapper.mapFindings(objectMapper.readTree("{}"))).isEmpty();
        assertThat(mapper.mapFindings(null)).isEmpty();
    }
}
