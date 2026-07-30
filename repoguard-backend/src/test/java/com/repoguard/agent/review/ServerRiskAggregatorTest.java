package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ServerRiskAggregatorTest {

    private final ServerRiskAggregator aggregator = new ServerRiskAggregator();

    @Test
    void singleUnverifiedHighCandidateIsMediumInsteadOfTrustingRootRisk() {
        assertThat(aggregator.aggregate(List.of(finding(
            "CRITICAL",
            "LLM",
            null,
            false,
            "HIGH",
            EnforcementMode.COMMENT.name(),
            "llm_candidate"
        )))).isEqualTo("MEDIUM");
    }

    @Test
    void lowConfidenceHighCandidateIsMediumButCannotBlock() {
        assertThat(aggregator.aggregate(List.of(finding(
            "HIGH",
            "RULE",
            "RG-AUTH-001",
            false,
            "LOW",
            EnforcementMode.BLOCK.name(),
            "confidence_below_block_threshold"
        )))).isEqualTo("MEDIUM");
    }

    @Test
    void verifiedBlockingCriticalAndHighProduceDeterministicRisk() {
        assertThat(aggregator.aggregate(List.of(finding(
            "CRITICAL",
            "RULE",
            "RG-DB-002",
            true,
            "HIGH",
            EnforcementMode.BLOCK.name(),
            "block_policy_satisfied"
        )))).isEqualTo("CRITICAL");
        assertThat(aggregator.aggregate(List.of(finding(
            "HIGH",
            "RULE",
            "RG-AUTH-001",
            true,
            "HIGH",
            EnforcementMode.BLOCK.name(),
            "block_policy_satisfied"
        )))).isEqualTo("HIGH");
    }

    @Test
    void twoIndependentHighEvidenceSourcesEscalateToHigh() {
        assertThat(aggregator.aggregate(List.of(
            finding("HIGH", "LLM", null, false, "HIGH", "COMMENT", "verified_candidate"),
            finding("HIGH", "RULE", "RG-SECRET-001", false, "HIGH", "COMMENT", "verified_candidate")
        ))).isEqualTo("HIGH");
    }

    @Test
    void observationAndFalsePositiveNeverContributeRisk() {
        assertThat(aggregator.aggregate(List.of(
            finding("CRITICAL", "RULE", "RG-DB-002", true, "HIGH", "OBSERVE", "block_policy_satisfied"),
            finding("HIGH", "RULE", "RG-AUTH-001", true, "HIGH", "BLOCK", "FALSE_POSITIVE")
        ))).isEqualTo("INFO");
    }

    private ReviewFindingResult finding(
        String severity,
        String source,
        String ruleId,
        boolean blocking,
        String confidence,
        String enforcement,
        String reason
    ) {
        return new ReviewFindingResult(
            severity,
            source,
            ruleId,
            "src/App.java",
            10,
            "Finding",
            "Fix",
            confidence,
            "Evidence",
            "Impact",
            "Fix",
            blocking,
            "SECURITY",
            enforcement,
            reason
        );
    }
}
