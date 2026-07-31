package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class LlmHighRiskVerificationParserTest {

    private final LlmHighRiskVerificationParser parser = new LlmHighRiskVerificationParser(
        new ObjectMapper(),
        new LlmReviewJsonExtractor()
    );

    @Test
    void parsesVersionedAdversarialDecision() {
        LlmHighRiskVerificationDecision decision = parser.parse("""
            ```json
            {
              "schemaVersion": "high-risk-verifier-v1",
              "verdict": "VERIFIED",
              "evidenceSupported": true,
              "preconditionsSatisfied": true,
              "addedLineValid": true,
              "protectionPresent": false,
              "existingProtection": "none",
              "confidence": "HIGH",
              "reason": "The new public route reaches the write without an authorization guard"
            }
            ```
            """);

        assertThat(decision.verified()).isTrue();
        assertThat(decision.confidence()).isEqualTo("HIGH");
        assertThat(decision.existingProtection()).isEqualTo("none");
    }

    @Test
    void acceptsRejectedDecisionButDoesNotTreatItAsVerified() {
        LlmHighRiskVerificationDecision decision = parser.parse(decisionJson(
            "REJECTED",
            false,
            false,
            true,
            true,
            "Class-level role guard",
            "HIGH"
        ));

        assertThat(decision.verdict()).isEqualTo(LlmHighRiskVerificationDecision.Verdict.REJECTED);
        assertThat(decision.verified()).isFalse();
    }

    @Test
    void rejectsMissingVersionAndNonBooleanEvidenceFields() {
        assertThatThrownBy(() -> parser.parse("""
            {
              "verdict": "VERIFIED",
              "evidenceSupported": true,
              "preconditionsSatisfied": true,
              "addedLineValid": true,
              "protectionPresent": false,
              "existingProtection": "none",
              "confidence": "HIGH",
              "reason": "supported"
            }
            """))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("schemaVersion");
        assertThatThrownBy(() -> parser.parse(decisionJson(
            "VERIFIED",
            "true",
            true,
            true,
            false,
            "none",
            "HIGH"
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("boolean");
    }

    @Test
    void rejectsUnsupportedSchemaVerdictAndConfidence() {
        assertThatThrownBy(() -> parser.parse(decisionJson(
            "VERIFIED",
            true,
            true,
            true,
            false,
            "none",
            "CERTAIN"
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("confidence");
        assertThatThrownBy(() -> parser.parse(decisionJson(
            "APPROVED",
            true,
            true,
            true,
            false,
            "none",
            "HIGH"
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("verdict");
        assertThatThrownBy(() -> parser.parse(decisionJson(
            "high-risk-verifier-v2",
            "VERIFIED",
            true,
            true,
            true,
            false,
            "none",
            "HIGH"
        )))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("schema version");
    }

    private String decisionJson(
        String verdict,
        Object evidenceSupported,
        boolean preconditionsSatisfied,
        boolean addedLineValid,
        boolean protectionPresent,
        String existingProtection,
        String confidence
    ) {
        return decisionJson(
            "high-risk-verifier-v1",
            verdict,
            evidenceSupported,
            preconditionsSatisfied,
            addedLineValid,
            protectionPresent,
            existingProtection,
            confidence
        );
    }

    private String decisionJson(
        String schemaVersion,
        String verdict,
        Object evidenceSupported,
        boolean preconditionsSatisfied,
        boolean addedLineValid,
        boolean protectionPresent,
        String existingProtection,
        String confidence
    ) {
        String evidenceJson = evidenceSupported instanceof Boolean
            ? evidenceSupported.toString()
            : "\"" + evidenceSupported + "\"";
        return """
            {
              "schemaVersion": "%s",
              "verdict": "%s",
              "evidenceSupported": %s,
              "preconditionsSatisfied": %s,
              "addedLineValid": %s,
              "protectionPresent": %s,
              "existingProtection": "%s",
              "confidence": "%s",
              "reason": "adversarial verification result"
            }
            """.formatted(
                schemaVersion,
                verdict,
                evidenceJson,
                preconditionsSatisfied,
                addedLineValid,
                protectionPresent,
                existingProtection,
                confidence
            );
    }
}
