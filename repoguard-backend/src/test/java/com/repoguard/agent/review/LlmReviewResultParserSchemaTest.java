package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class LlmReviewResultParserSchemaTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LlmReviewResultParser parser = new LlmReviewResultParser(
        objectMapper,
        new LlmReviewJsonExtractor(),
        new LlmReviewSchemaRepairer(objectMapper),
        new LlmReviewFindingMapper(),
        new LlmReviewParseFailureSummarizer()
    );

    @Test
    void repairsSingleFindingObjectAndNormalizesSchemaFields() {
        ReviewResult result = parser.parse("""
            {
              "risk": "urgent",
              "findings": {
                "level": "high",
                "file": "src/AdminController.java",
                "line": "-1",
                "issue": "Admin endpoint is public",
                "fix": "Require an admin role",
                "confidence": "certain",
                "blocking": "false",
                "review_dimension": "access_control"
              }
            }
            """);

        ReviewFindingResult finding = result.findings().getFirst();
        assertThat(result.riskLevel()).isEqualTo("INFO");
        assertThat(finding.severity()).isEqualTo("HIGH");
        assertThat(finding.filePath()).isEqualTo("src/AdminController.java");
        assertThat(finding.lineNumber()).isNull();
        assertThat(finding.message()).isEqualTo("Admin endpoint is public");
        assertThat(finding.recommendation()).isEqualTo("Require an admin role");
        assertThat(finding.confidence()).isEqualTo("HIGH");
        assertThat(finding.isBlocking()).isTrue();
        assertThat(finding.reviewDimension()).isEqualTo("access_control");
    }

    @Test
    void repairsMissingFindingFieldsWithSchemaSafeDefaults() {
        ReviewResult result = parser.parse("""
            {
              "riskLevel": "medium",
              "findings": [
                {
                  "severity": "unexpected"
                },
                "not an object"
              ]
            }
            """);

        ReviewFindingResult finding = result.findings().getFirst();
        assertThat(result.riskLevel()).isEqualTo("MEDIUM");
        assertThat(result.findings()).hasSize(1);
        assertThat(finding.severity()).isEqualTo("LOW");
        assertThat(finding.filePath()).isEqualTo("unknown");
        assertThat(finding.message()).isEqualTo("LLM review reported a potential issue.");
        assertThat(finding.recommendation()).isEqualTo("Review the surrounding context and apply a targeted fix.");
        assertThat(finding.confidence()).isEqualTo("MEDIUM");
        assertThat(finding.reviewDimension()).isEqualTo("LLM");
    }
}
