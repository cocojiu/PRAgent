package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class FindingPolicyResolverTest {

    private final FindingPolicyResolver resolver = new FindingPolicyResolver();
    private final RuleMatch match = new RuleMatch(
        "RG-AUTH-001",
        "src/AdminController.java",
        12,
        "Administrative endpoint has no authorization guard",
        "Require an administrator role",
        "Added mutating endpoint",
        "Unauthorized changes",
        "ACCESS_CONTROL",
        true
    );

    @Test
    void blocksOnlyVerifiedHighImpactHighConfidenceMatch() {
        EffectiveFinding finding = resolver.resolve(
            match,
            settings("HIGH", 95, EnforcementMode.BLOCK),
            EvidenceValidation.forRuleMatch(match)
        );

        assertThat(finding.severity()).isEqualTo("HIGH");
        assertThat(finding.confidence()).isEqualTo("HIGH");
        assertThat(finding.blocking()).isTrue();
        assertThat(finding.policyReason()).isEqualTo("block_policy_satisfied");
    }

    @Test
    void severityConfigurationControlsImpactWithoutDetectorChanges() {
        EffectiveFinding finding = resolver.resolve(
            match,
            settings("LOW", 95, EnforcementMode.BLOCK),
            EvidenceValidation.forRuleMatch(match)
        );

        assertThat(finding.severity()).isEqualTo("LOW");
        assertThat(finding.blocking()).isFalse();
        assertThat(finding.policyReason()).isEqualTo("severity_below_block_threshold");
    }

    @Test
    void missingAnchorAndUnverifiedEvidenceReduceConfidenceAndPreventBlocking() {
        RuleMatch weakMatch = new RuleMatch(
            match.ruleId(),
            match.filePath(),
            null,
            match.message(),
            match.recommendation(),
            match.evidence(),
            match.impact(),
            match.reviewDimension(),
            false
        );

        EffectiveFinding finding = resolver.resolve(
            weakMatch,
            settings("CRITICAL", 95, EnforcementMode.BLOCK),
            EvidenceValidation.forRuleMatch(weakMatch)
        );

        assertThat(finding.confidenceScore()).isEqualTo(35);
        assertThat(finding.confidence()).isEqualTo("LOW");
        assertThat(finding.blocking()).isFalse();
        assertThat(finding.policyReason()).contains("missing_changed_line_anchor");
    }

    private ReviewRuleSettings settings(String severity, int confidence, EnforcementMode mode) {
        return new ReviewRuleSettings(
            match.ruleId(),
            "ENABLED",
            "*.java",
            severity,
            confidence,
            mode,
            "example",
            "guidance"
        );
    }
}
