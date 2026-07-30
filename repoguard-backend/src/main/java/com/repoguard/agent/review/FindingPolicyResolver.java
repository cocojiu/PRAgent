package com.repoguard.agent.review;

import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
class FindingPolicyResolver {

    private static final int HIGH_CONFIDENCE_THRESHOLD = 80;

    EffectiveFinding resolve(
        RuleMatch match,
        ReviewRuleSettings settings,
        EvidenceValidation validation
    ) {
        Objects.requireNonNull(match, "match");
        Objects.requireNonNull(settings, "settings");
        Objects.requireNonNull(validation, "validation");
        if (!settings.id().equals(match.ruleId())) {
            throw new IllegalArgumentException(
                "Rule match id " + match.ruleId() + " does not match configuration " + settings.id()
            );
        }
        int effectiveConfidence = Math.max(0, settings.confidence() - validation.confidencePenalty());
        String confidence = confidenceLabel(effectiveConfidence);
        boolean highImpact = "HIGH".equals(settings.severity()) || "CRITICAL".equals(settings.severity());
        boolean blocking = settings.enforcementMode() == EnforcementMode.BLOCK
            && highImpact
            && effectiveConfidence >= HIGH_CONFIDENCE_THRESHOLD
            && validation.anchorValid()
            && validation.evidenceVerified();
        String reason = blocking
            ? "block_policy_satisfied"
            : nonBlockingReason(settings, validation, highImpact, effectiveConfidence);
        return new EffectiveFinding(
            match,
            settings.severity(),
            effectiveConfidence,
            confidence,
            settings.enforcementMode(),
            blocking,
            reason
        );
    }

    private String nonBlockingReason(
        ReviewRuleSettings settings,
        EvidenceValidation validation,
        boolean highImpact,
        int effectiveConfidence
    ) {
        if (settings.enforcementMode() != EnforcementMode.BLOCK) {
            return "enforcement_" + settings.enforcementMode().name().toLowerCase();
        }
        if (!highImpact) {
            return "severity_below_block_threshold";
        }
        if (!validation.anchorValid() || !validation.evidenceVerified()) {
            return validation.reason();
        }
        if (effectiveConfidence < HIGH_CONFIDENCE_THRESHOLD) {
            return "confidence_below_block_threshold";
        }
        return validation.reason();
    }

    private String confidenceLabel(int score) {
        if (score >= HIGH_CONFIDENCE_THRESHOLD) {
            return "HIGH";
        }
        if (score >= 55) {
            return "MEDIUM";
        }
        return "LOW";
    }
}
