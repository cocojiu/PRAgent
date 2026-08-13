package com.repoguard.agent.review.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.repoguard.agent.review.EnforcementMode;
import com.repoguard.agent.review.LlmReviewVersions;
import com.repoguard.agent.review.ReviewStrategyRelease;
import com.repoguard.agent.review.ServerRiskAggregator;
import com.repoguard.agent.review.quality.ReviewQualityGroupBaseline;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewPolicyLifecycleGateTest {

    private final ReviewRuleLifecycleGate ruleGate = new ReviewRuleLifecycleGate();
    private final ReviewStrategyLifecycleGate strategyGate = new ReviewStrategyLifecycleGate();

    @Test
    void ruleGateDoesNotGradeOrBlockBeforeThirtyExplicitHighRiskSamples() {
        var gate = ruleGate.evaluate(
            "RG-JAVA-001",
            "rg-java-001-detector-v2",
            3,
            List.of(group(
                "RG-JAVA-001",
                "RULE",
                "HIGH",
                "rg-java-001-detector-v2",
                3,
                LlmReviewVersions.PROMPT,
                LlmReviewVersions.CONTEXT,
                LlmReviewVersions.SCHEMA,
                LlmReviewVersions.VERIFIER,
                29,
                29,
                29,
                0,
                29,
                0
            ))
        );

        assertThat(gate.commentEligible()).isTrue();
        assertThat(gate.blockEligible()).isFalse();
        assertThat(gate.status()).isEqualTo("INSUFFICIENT_SAMPLE");
        assertThat(gate.blockers()).containsExactly("labeled_high_risk_samples_below_30");
    }

    @Test
    void ruleGateRequiresTheCurrentDetectorAndConfigVersion() {
        var gate = ruleGate.evaluate(
            "RG-JAVA-001",
            "rg-java-001-detector-v3",
            4,
            List.of(group(
                "RG-JAVA-001",
                "RULE",
                "HIGH",
                "rg-java-001-detector-v2",
                4,
                LlmReviewVersions.PROMPT,
                LlmReviewVersions.CONTEXT,
                LlmReviewVersions.SCHEMA,
                LlmReviewVersions.VERIFIER,
                40,
                40,
                40,
                0,
                40,
                0
            ))
        );

        assertThat(gate.labeledSamples()).isZero();
        assertThat(gate.commentEligible()).isFalse();
        assertThat(gate.status()).isEqualTo("INSUFFICIENT_SAMPLE");
    }

    @Test
    void ruleGateIncludesMergedLlmAndRuleDetectorProvenance() {
        var gate = ruleGate.evaluate(
            "RG-JAVA-001",
            "rg-java-001-detector-v2",
            3,
            List.of(group(
                "LLM / RG-JAVA-001",
                "LLM+RULE",
                "HIGH",
                "llm-review-v2+rg-java-001-detector-v2",
                3,
                LlmReviewVersions.PROMPT,
                LlmReviewVersions.CONTEXT,
                LlmReviewVersions.SCHEMA,
                LlmReviewVersions.VERIFIER,
                40,
                40,
                40,
                0,
                40,
                0
            ))
        );

        assertThat(gate.labeledHighRiskSamples()).isEqualTo(40);
        assertThat(gate.blockEligible()).isTrue();
        assertThat(gate.status()).isEqualTo("PASS");
    }

    @Test
    void ruleGateBlocksWhenPointPrecisionLacksStatisticalConfidence() {
        var gate = ruleGate.evaluate(
            "RG-JAVA-001",
            "rg-java-001-detector-v2",
            3,
            List.of(group(
                "RG-JAVA-001",
                "RULE",
                "HIGH",
                "rg-java-001-detector-v2",
                3,
                LlmReviewVersions.PROMPT,
                LlmReviewVersions.CONTEXT,
                LlmReviewVersions.SCHEMA,
                LlmReviewVersions.VERIFIER,
                30,
                30,
                27,
                3,
                29,
                1
            ))
        );

        assertThat(gate.blockEligible()).isFalse();
        assertThat(gate.status()).isEqualTo("ALERT");
        assertThat(gate.precision()).isEqualByComparingTo("90.00");
        assertThat(gate.falsePositiveRate()).isEqualByComparingTo("10.00");
        assertThat(gate.anchorRate()).isEqualByComparingTo("96.67");
        assertThat(gate.duplicateRate()).isEqualByComparingTo("3.33");
        assertThat(gate.blockers()).containsExactly("precision_wilson_lower_bound_below_90");
    }

    @Test
    void ruleGateRejectsInconsistentLabeledOutcomeCounts() {
        var gate = ruleGate.evaluate(
            "RG-JAVA-001",
            "rg-java-001-detector-v2",
            3,
            List.of(group(
                "RG-JAVA-001",
                "RULE",
                "HIGH",
                "rg-java-001-detector-v2",
                3,
                LlmReviewVersions.PROMPT,
                LlmReviewVersions.CONTEXT,
                LlmReviewVersions.SCHEMA,
                LlmReviewVersions.VERIFIER,
                100,
                100,
                95,
                4,
                100,
                0
            ))
        );

        assertThat(gate.blockEligible()).isFalse();
        assertThat(gate.blockers()).contains("labeled_outcome_counts_inconsistent");
    }

    @Test
    void ruleGateDoesNotPromoteARoundedUpWilsonLowerBound() {
        var gate = ruleGate.evaluate(
            "RG-JAVA-001",
            "rg-java-001-detector-v2",
            3,
            List.of(group(
                "RG-JAVA-001",
                "RULE",
                "HIGH",
                "rg-java-001-detector-v2",
                3,
                LlmReviewVersions.PROMPT,
                LlmReviewVersions.CONTEXT,
                LlmReviewVersions.SCHEMA,
                LlmReviewVersions.VERIFIER,
                126,
                126,
                120,
                6,
                126,
                0
            ))
        );

        assertThat(gate.precision()).isEqualByComparingTo("95.24");
        assertThat(gate.blockEligible()).isFalse();
        assertThat(gate.blockers()).containsExactly("precision_wilson_lower_bound_below_90");
    }

    @Test
    void strategyGateRejectsMetricsFromAnotherContextOrVerifierVersion() {
        ReviewStrategyRelease release = release(EnforcementMode.COMMENT);
        List<ReviewQualityGroupBaseline> groups = List.of(
            group(
                "UNASSIGNED",
                "LLM",
                "HIGH",
                "llm-review-v2",
                1,
                LlmReviewVersions.PROMPT,
                "review-context-v1",
                LlmReviewVersions.SCHEMA,
                LlmReviewVersions.VERIFIER,
                30,
                30,
                30,
                0,
                30,
                0
            ),
            group(
                "UNASSIGNED",
                "LLM",
                "HIGH",
                "llm-review-v2",
                1,
                LlmReviewVersions.PROMPT,
                LlmReviewVersions.CONTEXT,
                LlmReviewVersions.SCHEMA,
                "high-risk-verifier-v0",
                30,
                30,
                30,
                0,
                30,
                0
            )
        );

        var gate = strategyGate.evaluate(release, groups);

        assertThat(gate.labeledSamples()).isZero();
        assertThat(gate.blockEligible()).isFalse();
        assertThat(gate.status()).isEqualTo("INSUFFICIENT_SAMPLE");
    }

    private ReviewStrategyRelease release(EnforcementMode mode) {
        return new ReviewStrategyRelease(
            1,
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

    private ReviewQualityGroupBaseline group(
        String ruleId,
        String source,
        String severity,
        String detectorVersion,
        long configVersion,
        String promptVersion,
        String contextVersion,
        String schemaVersion,
        String verifierVersion,
        long total,
        long labeled,
        long confirmed,
        long falsePositives,
        long anchored,
        long duplicates
    ) {
        return new ReviewQualityGroupBaseline(
            ruleId,
            source,
            "octocat/demo",
            "JAVA",
            severity,
            "test-version",
            detectorVersion,
            configVersion,
            1,
            promptVersion,
            contextVersion,
            schemaVersion,
            verifierVersion,
            ServerRiskAggregator.VERSION,
            total,
            labeled,
            BigDecimal.ZERO,
            confirmed,
            falsePositives,
            total - labeled,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            total,
            BigDecimal.ZERO,
            0,
            BigDecimal.ZERO,
            0,
            anchored,
            BigDecimal.ZERO,
            duplicates,
            BigDecimal.ZERO,
            "INSUFFICIENT_SAMPLE",
            List.of()
        );
    }
}
