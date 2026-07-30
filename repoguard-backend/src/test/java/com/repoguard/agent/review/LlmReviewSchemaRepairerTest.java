package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

class LlmReviewSchemaRepairerTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LlmReviewSchemaRepairer repairer = new LlmReviewSchemaRepairer(objectMapper);

    @Test
    void repairsAliasesAndSchemaDefaults() throws Exception {
        ObjectNode repaired = repairer.repairAndValidateRoot(objectMapper.readTree("""
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
            """));

        assertThat(repaired.path("riskLevel").asText()).isEqualTo("INFO");
        assertThat(repaired.path("findings")).hasSize(1);
        assertThat(repaired.path("findings").get(0).path("severity").asText()).isEqualTo("HIGH");
        assertThat(repaired.path("findings").get(0).path("lineNumber").isNull()).isTrue();
        assertThat(repaired.path("findings").get(0).path("confidence").asText()).isEqualTo("HIGH");
        assertThat(repaired.path("findings").get(0).path("isBlocking").asBoolean()).isFalse();
        assertThat(repaired.path("findings").get(0).path("reviewDimension").asText()).isEqualTo("access_control");
    }

    @Test
    void dropsNonObjectFindingsAndFillsRequiredFields() throws Exception {
        ObjectNode repaired = repairer.repairAndValidateRoot(objectMapper.readTree("""
            {
              "riskLevel": "medium",
              "findings": [
                {"severity": "unexpected"},
                "not an object"
              ]
            }
            """));

        assertThat(repaired.path("riskLevel").asText()).isEqualTo("MEDIUM");
        assertThat(repaired.path("findings")).hasSize(1);
        assertThat(repaired.path("findings").get(0).path("severity").asText()).isEqualTo("LOW");
        assertThat(repaired.path("findings").get(0).path("filePath").asText()).isEqualTo("unknown");
        assertThat(repaired.path("findings").get(0).path("message").asText())
            .isEqualTo("LLM review reported a potential issue.");
        assertThat(repaired.path("findings").get(0).path("recommendation").asText())
            .isEqualTo("Review the surrounding context and apply a targeted fix.");
        assertThat(repaired.path("findings").get(0).path("reviewDimension").asText()).isEqualTo("LLM");
    }

    @Test
    void rejectsNonObjectRoot() throws Exception {
        assertThatThrownBy(() -> repairer.repairAndValidateRoot(objectMapper.readTree("[]")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("root must be a JSON object");
    }
}
