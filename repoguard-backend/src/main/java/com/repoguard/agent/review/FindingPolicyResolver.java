package com.repoguard.agent.review;

import java.util.Locale;
import java.util.Objects;
import org.springframework.util.StringUtils;
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

    ReviewFindingResult resolveVerifiedLlmCandidate(
        ReviewFindingResult candidate,
        LlmHighRiskVerificationDecision decision,
        EnforcementMode configuredMode
    ) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(decision, "decision");
        Objects.requireNonNull(configuredMode, "configuredMode");
        if (!decision.verified()) {
            LlmVerificationStatus status = decision.verdict() == LlmHighRiskVerificationDecision.Verdict.UNCERTAIN
                ? LlmVerificationStatus.UNCERTAIN
                : LlmVerificationStatus.REJECTED;
            return downgradeLlmCandidate(candidate, status, decision.reason());
        }

        boolean highConfidence = "HIGH".equalsIgnoreCase(candidate.confidence())
            && "HIGH".equalsIgnoreCase(decision.confidence());
        String confidence = highConfidence ? "HIGH" : "MEDIUM";
        boolean highImpact = isHighImpact(candidate.severity());
        boolean blocking = configuredMode == EnforcementMode.BLOCK
            && candidate.blockingCandidate()
            && highImpact
            && highConfidence
            && candidate.lineNumber() != null
            && candidate.lineNumber() > 0;
        EnforcementMode effectiveMode = blocking
            ? EnforcementMode.BLOCK
            : configuredMode == EnforcementMode.OBSERVE ? EnforcementMode.OBSERVE : EnforcementMode.COMMENT;
        String reason = blocking
            ? "llm_verified_server_block_policy_satisfied"
            : "llm_verified_server_policy_" + effectiveMode.name().toLowerCase(Locale.ROOT);
        return copyLlm(
            candidate,
            candidate.severity(),
            confidence,
            appendEvidence(candidate.evidence(), decision),
            blocking,
            effectiveMode,
            reason,
            candidate.blockingCandidate(),
            LlmVerificationStatus.VERIFIED
        );
    }

    ReviewFindingResult downgradeLlmCandidate(
        ReviewFindingResult candidate,
        LlmVerificationStatus status,
        String reason
    ) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(status, "status");
        String severity = isHighImpact(candidate.severity()) ? "MEDIUM" : candidate.severity();
        String confidence = "LOW".equalsIgnoreCase(candidate.confidence()) ? "LOW" : "MEDIUM";
        return copyLlm(
            candidate,
            severity,
            confidence,
            appendText(candidate.evidence(), "verification=" + status.name().toLowerCase(Locale.ROOT)
                + "; reason=" + normalizedReason(reason)),
            false,
            EnforcementMode.OBSERVE,
            "llm_verification_" + status.name().toLowerCase(Locale.ROOT),
            false,
            status
        );
    }

    private ReviewFindingResult copyLlm(
        ReviewFindingResult candidate,
        String severity,
        String confidence,
        String evidence,
        boolean blocking,
        EnforcementMode enforcementMode,
        String policyReason,
        boolean blockingCandidate,
        LlmVerificationStatus verificationStatus
    ) {
        return new ReviewFindingResult(
            severity,
            candidate.source(),
            candidate.ruleId(),
            candidate.filePath(),
            candidate.lineNumber(),
            candidate.message(),
            candidate.recommendation(),
            confidence,
            evidence,
            candidate.impact(),
            candidate.fixExample(),
            blocking,
            candidate.reviewDimension(),
            enforcementMode.name(),
            policyReason,
            candidate.issueType(),
            candidate.preconditions(),
            candidate.relatedFiles(),
            blockingCandidate,
            verificationStatus.name()
        );
    }

    private String appendEvidence(
        String evidence,
        LlmHighRiskVerificationDecision decision
    ) {
        return appendText(
            evidence,
            "verification=verified; confidence=" + decision.confidence().toLowerCase(Locale.ROOT)
                + "; protection=" + normalizedReason(decision.existingProtection())
                + "; reason=" + normalizedReason(decision.reason())
        );
    }

    private String appendText(String current, String additional) {
        if (!StringUtils.hasText(current)) {
            return additional;
        }
        return current.trim() + " | " + additional;
    }

    private String normalizedReason(String value) {
        if (!StringUtils.hasText(value)) {
            return "unspecified";
        }
        String normalized = value.trim().replaceAll("\\s+", " ");
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500);
    }

    private boolean isHighImpact(String severity) {
        return "HIGH".equalsIgnoreCase(severity) || "CRITICAL".equalsIgnoreCase(severity);
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
