package com.repoguard.agent.review;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.repoguard.agent.entity.ReviewFinding;
import com.repoguard.agent.mapper.ReviewFindingMapper;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class ReviewFindingRiskRecalibrator {

    private final ReviewFindingMapper reviewFindingMapper;
    private final ServerRiskAggregator riskAggregator;
    private final HumanReviewPolicyEvaluator humanReviewPolicyEvaluator;

    public ReviewFindingRiskRecalibrator(
        ReviewFindingMapper reviewFindingMapper,
        ServerRiskAggregator riskAggregator,
        HumanReviewPolicyEvaluator humanReviewPolicyEvaluator
    ) {
        this.reviewFindingMapper = Objects.requireNonNull(reviewFindingMapper, "reviewFindingMapper");
        this.riskAggregator = Objects.requireNonNull(riskAggregator, "riskAggregator");
        this.humanReviewPolicyEvaluator = Objects.requireNonNull(
            humanReviewPolicyEvaluator,
            "humanReviewPolicyEvaluator"
        );
    }

    public Outcome recalculate(Long taskId) {
        List<ReviewFindingResult> effectiveFindings = reviewFindingMapper.selectList(
            new LambdaQueryWrapper<ReviewFinding>()
                .eq(ReviewFinding::getTaskId, taskId)
                .eq(ReviewFinding::getCurrentAttempt, true)
                .eq(ReviewFinding::getCategory, "FINDING")
        ).stream()
            .filter(finding -> FindingFeedbackStatus.fromFinding(finding) != FindingFeedbackStatus.FALSE_POSITIVE)
            .map(this::toFindingResult)
            .toList();
        return new Outcome(
            riskAggregator.aggregate(effectiveFindings),
            humanReviewPolicyEvaluator.requiresHumanReview(effectiveFindings)
        );
    }

    private ReviewFindingResult toFindingResult(ReviewFinding finding) {
        boolean blocking = Boolean.TRUE.equals(finding.getIsBlocking());
        String enforcementMode = finding.getEnforcementMode();
        if (enforcementMode == null || enforcementMode.isBlank()) {
            enforcementMode = blocking ? EnforcementMode.BLOCK.name() : EnforcementMode.COMMENT.name();
        }
        return new ReviewFindingResult(
            finding.getSeverity(),
            finding.getSource(),
            finding.getRuleId(),
            finding.getFilePath(),
            finding.getLineNumber(),
            finding.getMessage(),
            finding.getRecommendation(),
            finding.getConfidence(),
            finding.getEvidence(),
            finding.getImpact(),
            finding.getFixExample(),
            blocking,
            finding.getReviewDimension(),
            enforcementMode,
            finding.getPolicyReason()
        );
    }

    public record Outcome(String riskLevel, boolean humanReviewRequired) {}
}
