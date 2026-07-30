package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewFindingSemanticDeduplicatorTest {

    private final ReviewFindingSemanticDeduplicator deduplicator =
        new ReviewFindingSemanticDeduplicator();

    @Test
    void mergesRuleAndLlmFindingsForSameSemanticIssueAndAnchor() {
        ReviewFindingResult ruleFinding = finding(
            "HIGH",
            "RULE",
            "RG-SECRET-001",
            "Hard-coded token detected",
            true,
            "BLOCK"
        );
        ReviewFindingResult llmFinding = finding(
            "MEDIUM",
            "LLM",
            null,
            "Credential token is embedded in source",
            false,
            "COMMENT"
        );

        List<ReviewFindingResult> result = deduplicator.deduplicate(List.of(llmFinding, ruleFinding));

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().source()).isEqualTo("LLM+RULE");
        assertThat(result.getFirst().severity()).isEqualTo("HIGH");
        assertThat(result.getFirst().isBlocking()).isTrue();
        assertThat(result.getFirst().ruleId()).isEqualTo("RG-SECRET-001");
    }

    @Test
    void keepsSameIssueOnDifferentChangedLinesIndependent() {
        ReviewFindingResult first = finding("HIGH", "RULE", "RG-SECRET-001", "Token exposed", true, "BLOCK");
        ReviewFindingResult second = new ReviewFindingResult(
            first.severity(),
            "LLM",
            null,
            first.filePath(),
            11,
            "Another token exposed",
            first.recommendation(),
            first.confidence(),
            first.evidence(),
            first.impact(),
            first.fixExample(),
            false,
            first.reviewDimension(),
            "COMMENT",
            "llm_candidate"
        );

        assertThat(deduplicator.deduplicate(List.of(first, second))).hasSize(2);
    }

    @Test
    void ruleConsensusBoostsConfidenceButNeverCreatesABlockingDecision() {
        ReviewFindingResult ruleFinding = new ReviewFindingResult(
            "HIGH",
            "RULE",
            "RG-AUTH-001",
            "src/AdminController.java",
            12,
            "Authorization is missing from the new administrative route",
            "Require an administrative role",
            "MEDIUM",
            "The rule found a mutable route without a local role annotation",
            "Unauthorized state change",
            "Add @RequireRole",
            false,
            "SECURITY",
            "COMMENT",
            "confidence_below_block_threshold",
            "RG-AUTH-001",
            "The route is externally reachable",
            List.of(),
            false,
            "NOT_REQUIRED"
        );
        ReviewFindingResult llmFinding = new ReviewFindingResult(
            "HIGH",
            "LLM",
            null,
            "src/AdminController.java",
            12,
            "The new route lacks authorization",
            "Require an administrative role",
            "HIGH",
            "The verifier found no applicable guard",
            "Unauthorized state change",
            "Add @RequireRole",
            false,
            "SECURITY",
            "COMMENT",
            "llm_verified_server_policy_comment",
            "MISSING_AUTHORIZATION",
            "The route is externally reachable",
            List.of("src/SecurityConfig.java"),
            true,
            "VERIFIED"
        );

        ReviewFindingResult merged = deduplicator.deduplicate(List.of(ruleFinding, llmFinding)).getFirst();

        assertThat(merged.source()).isEqualTo("LLM+RULE");
        assertThat(merged.confidence()).isEqualTo("HIGH");
        assertThat(merged.policyReason()).contains("server_consensus_confidence_boost");
        assertThat(merged.verificationStatus()).isEqualTo("VERIFIED");
        assertThat(merged.relatedFiles()).containsExactly("src/SecurityConfig.java");
        assertThat(merged.blockingCandidate()).isTrue();
        assertThat(merged.isBlocking()).isFalse();
    }

    private ReviewFindingResult finding(
        String severity,
        String source,
        String ruleId,
        String message,
        boolean blocking,
        String enforcement
    ) {
        return new ReviewFindingResult(
            severity,
            source,
            ruleId,
            "src/Secrets.java",
            10,
            message,
            "Load credentials from a secret store",
            "HIGH",
            "Added line 10",
            "Credential exposure",
            "Use a secret store",
            blocking,
            "SECURITY",
            enforcement,
            blocking ? "block_policy_satisfied" : "llm_candidate"
        );
    }
}
