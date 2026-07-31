package com.repoguard.agent.review;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.repoguard.agent.config.ReviewHumanReviewProperties;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import java.util.List;
import org.junit.jupiter.api.Test;

class ReviewFindingRiskRecalibratorTest {

    private final ReviewFindingMapper reviewFindingMapper =
        org.mockito.Mockito.mock(ReviewFindingMapper.class);
    private final ReviewFindingRiskRecalibrator recalibrator =
        new ReviewFindingRiskRecalibrator(
            reviewFindingMapper,
            new ServerRiskAggregator(),
            new HumanReviewPolicyEvaluator(new RiskLevelRanker(), new ReviewHumanReviewProperties())
        );

    @Test
    void falsePositiveDoesNotContributeRiskOrHumanReviewGate() {
        ReviewFinding falsePositive = finding("HIGH", true, "BLOCK");
        falsePositive.setFeedbackStatus("FALSE_POSITIVE");
        ReviewFinding validLow = finding("LOW", false, "COMMENT");
        validLow.setRuleId("RG-JAVA-004");
        when(reviewFindingMapper.selectList(any())).thenReturn(List.of(falsePositive, validLow));

        ReviewFindingRiskRecalibrator.Outcome outcome = recalibrator.recalculate(42L);

        assertThat(outcome.riskLevel()).isEqualTo("LOW");
        assertThat(outcome.humanReviewRequired()).isFalse();
    }

    @Test
    void verifiedBlockingHighFindingRequiresHumanReview() {
        when(reviewFindingMapper.selectList(any())).thenReturn(List.of(finding("HIGH", true, "BLOCK")));

        ReviewFindingRiskRecalibrator.Outcome outcome = recalibrator.recalculate(42L);

        assertThat(outcome.riskLevel()).isEqualTo("HIGH");
        assertThat(outcome.humanReviewRequired()).isTrue();
    }

    private ReviewFinding finding(String severity, boolean blocking, String enforcementMode) {
        ReviewFinding finding = new ReviewFinding();
        finding.setTaskId(42L);
        finding.setCategory("FINDING");
        finding.setSeverity(severity);
        finding.setSource("RULE");
        finding.setRuleId("RG-AUTH-001");
        finding.setFilePath("src/AdminController.java");
        finding.setLineNumber(12);
        finding.setMessage("Finding");
        finding.setRecommendation("Fix");
        finding.setConfidence("HIGH");
        finding.setEvidence("Verified added line");
        finding.setImpact("Impact");
        finding.setFixExample("Fix");
        finding.setIsBlocking(blocking);
        finding.setEnforcementMode(enforcementMode);
        finding.setPolicyReason(blocking ? "block_policy_satisfied" : "enforcement_comment");
        finding.setReviewDimension("ACCESS_CONTROL");
        return finding;
    }
}
