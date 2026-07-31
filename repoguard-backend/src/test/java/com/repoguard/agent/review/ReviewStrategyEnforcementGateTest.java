package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewStrategyEnforcementGateTest {

    private final ReviewStrategyEnforcementGate gate = new ReviewStrategyEnforcementGate();

    @Test
    void observeReleaseCapsRuleAndLlmFindingsWithoutLosingOriginalCandidates() {
        ReviewExecutionProvenance execution = ReviewExecutionProvenance.from(release(EnforcementMode.OBSERVE));
        ReviewResult input = new ReviewResult(
            "HIGH",
            "COMPLETED",
            null,
            List.of(
                finding("RULE", "RG-AUTH-001", "BLOCK", true, false),
                finding("LLM", null, "COMMENT", false, true)
            ),
            "dashscope",
            "qwen",
            42,
            "parsed",
            "summary",
            10,
            5,
            15,
            BigDecimal.ONE,
            execution
        );

        ReviewResult result = gate.apply(input, release(EnforcementMode.OBSERVE));

        assertThat(result.riskLevel()).isEqualTo("INFO");
        assertThat(result.findings()).allSatisfy(finding -> {
            assertThat(finding.enforcementMode()).isEqualTo("OBSERVE");
            assertThat(finding.isBlocking()).isFalse();
            assertThat(finding.policyReason()).contains("strategy_enforcement_cap_observe");
        });
        assertThat(result.findings().getFirst().blockingCandidate()).isTrue();
        assertThat(result.llmTotalTokens()).isEqualTo(15);
        assertThat(result.executionProvenance()).isEqualTo(execution);
    }

    @Test
    void commentReleaseCapsBlockButNeverPromotesObserve() {
        ReviewResult input = ReviewResult.completed(
            "HIGH",
            List.of(
                finding("RULE", "RG-AUTH-001", "BLOCK", true, false),
                finding("LLM", null, "OBSERVE", false, false)
            )
        );

        ReviewResult result = gate.apply(input, release(EnforcementMode.COMMENT));

        assertThat(result.findings().getFirst().enforcementMode()).isEqualTo("COMMENT");
        assertThat(result.findings().getFirst().isBlocking()).isFalse();
        assertThat(result.findings().get(1).enforcementMode()).isEqualTo("OBSERVE");
        assertThat(result.riskLevel()).isEqualTo("MEDIUM");
    }

    @Test
    void unsupportedOrUnverifiedReleaseFailsClosedToObserve() {
        ReviewStrategyRelease unverified = new ReviewStrategyRelease(
            9,
            1,
            LlmReviewVersions.PROMPT,
            LlmReviewVersions.CONTEXT,
            LlmReviewVersions.SCHEMA,
            LlmReviewVersions.VERIFIER,
            ServerRiskAggregator.VERSION,
            EnforcementMode.BLOCK,
            false
        );

        ReviewResult result = gate.apply(
            ReviewResult.completed("HIGH", List.of(finding("RULE", "RG-AUTH-001", "BLOCK", true, false))),
            unverified
        );

        assertThat(result.riskLevel()).isEqualTo("INFO");
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.enforcementMode()).isEqualTo("OBSERVE");
            assertThat(finding.isBlocking()).isFalse();
        });
    }

    @Test
    void invalidFindingModeIsNormalizedAndRetainsAuditMarker() {
        ReviewFindingResult invalid = finding(
            "RULE",
            "RG-AUTH-001",
            "unexpected",
            false,
            false,
            "x".repeat(255)
        );

        ReviewResult result = gate.apply(
            ReviewResult.completed("HIGH", List.of(invalid)),
            release(EnforcementMode.OBSERVE)
        );

        assertThat(result.riskLevel()).isEqualTo("INFO");
        assertThat(result.findings()).singleElement().satisfies(finding -> {
            assertThat(finding.enforcementMode()).isEqualTo("OBSERVE");
            assertThat(finding.policyReason())
                .hasSize(255)
                .endsWith("strategy_enforcement_cap_observe");
        });
    }

    private ReviewFindingResult finding(
        String source,
        String ruleId,
        String mode,
        boolean blocking,
        boolean blockingCandidate
    ) {
        return finding(
            source,
            ruleId,
            mode,
            blocking,
            blockingCandidate,
            blocking ? "block_policy_satisfied" : "comment_policy"
        );
    }

    private ReviewFindingResult finding(
        String source,
        String ruleId,
        String mode,
        boolean blocking,
        boolean blockingCandidate,
        String policyReason
    ) {
        return new ReviewFindingResult(
            "HIGH",
            source,
            ruleId,
            "src/main/java/ExampleController.java",
            12,
            "missing authorization",
            "add authorization",
            "HIGH",
            "added write endpoint",
            "unauthorized state change",
            "add @RequireRole",
            blocking,
            "SECURITY",
            mode,
            policyReason,
            "MissingAuthorizationBoundary",
            "caller can reach endpoint",
            List.of(),
            blockingCandidate,
            "VERIFIED",
            FindingProvenance.legacy(source, ruleId, "HIGH", "HIGH")
        );
    }

    private ReviewStrategyRelease release(EnforcementMode mode) {
        return new ReviewStrategyRelease(
            17,
            1,
            LlmReviewVersions.PROMPT,
            LlmReviewVersions.CONTEXT,
            LlmReviewVersions.SCHEMA,
            LlmReviewVersions.VERIFIER,
            ServerRiskAggregator.VERSION,
            mode,
            true
        );
    }
}
