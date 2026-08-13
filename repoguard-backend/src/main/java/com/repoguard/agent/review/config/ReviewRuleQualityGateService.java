package com.repoguard.agent.review.config;

import com.repoguard.agent.common.BusinessException;
import com.repoguard.agent.common.ErrorCode;
import com.repoguard.agent.dto.ReviewCalibrationQueueDto;
import com.repoguard.agent.dto.ReviewRuleQualityGateDto;
import com.repoguard.agent.entity.ReviewRulePolicySnapshot;
import com.repoguard.agent.review.EnforcementMode;
import com.repoguard.agent.review.config.ReviewPolicyPromotionEvidenceStore.CapturedPromotionEvidence;
import com.repoguard.agent.service.ReviewCalibrationService;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class ReviewRuleQualityGateService {

    private final ReviewCalibrationService reviewCalibrationService;
    private final ReviewPolicyPromotionEvidenceStore promotionEvidenceStore;

    public ReviewRuleQualityGateService(
        ReviewCalibrationService reviewCalibrationService,
        ReviewPolicyPromotionEvidenceStore promotionEvidenceStore
    ) {
        this.reviewCalibrationService = Objects.requireNonNull(reviewCalibrationService, "reviewCalibrationService");
        this.promotionEvidenceStore = Objects.requireNonNull(promotionEvidenceStore, "promotionEvidenceStore");
    }

    PromotionEvaluation evaluatePromotion(String ruleId) {
        ReviewCalibrationQueueDto queue = reviewCalibrationService.getQueue(ruleId, 1, false);
        if (queue == null || queue.qualityGate() == null) {
            throw new IllegalStateException("Rule promotion quality gate is unavailable");
        }
        return new PromotionEvaluation(queue.qualityGate(), queue);
    }

    void validateTransition(
        EnforcementMode current,
        EnforcementMode target,
        ReviewRuleQualityGateDto qualityGate
    ) {
        if (rank(target) <= rank(current)) {
            return;
        }
        if (current == EnforcementMode.OBSERVE && target == EnforcementMode.BLOCK) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "Review rule must pass COMMENT before BLOCK");
        }
        if (target == EnforcementMode.COMMENT && !qualityGate.commentEligible()) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "At least one explicit labeled sample is required before COMMENT"
            );
        }
        if (target == EnforcementMode.BLOCK && !qualityGate.blockEligible()) {
            throw new BusinessException(
                ErrorCode.BAD_REQUEST,
                "BLOCK quality gate failed: " + String.join(",", qualityGate.blockers())
            );
        }
    }

    CapturedPromotionEvidence capturePromotion(
        EnforcementMode current,
        EnforcementMode target,
        PromotionEvaluation evaluation
    ) {
        CapturedPromotionEvidence evidence = promotionEvidenceStore.captureRulePromotion(
            current,
            target,
            evaluation.calibrationQueue()
        );
        if (evidence == null) {
            throw new IllegalStateException("Rule promotion evidence capture is unavailable");
        }
        return evidence;
    }

    void recordPromotion(ReviewRulePolicySnapshot snapshot, CapturedPromotionEvidence evidence) {
        promotionEvidenceStore.recordRulePromotion(snapshot, evidence);
    }

    private int rank(EnforcementMode mode) {
        return switch (mode) {
            case OBSERVE -> 1;
            case COMMENT -> 2;
            case BLOCK -> 3;
        };
    }

    record PromotionEvaluation(
        ReviewRuleQualityGateDto qualityGate,
        ReviewCalibrationQueueDto calibrationQueue
    ) {
    }
}
