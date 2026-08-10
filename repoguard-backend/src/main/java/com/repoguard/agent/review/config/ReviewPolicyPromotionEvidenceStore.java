package com.repoguard.agent.review.config;

import com.repoguard.agent.dto.ReviewCalibrationQueueDto;
import com.repoguard.agent.dto.ReviewCalibrationVersionDto;
import com.repoguard.agent.dto.ReviewRuleQualityGateDto;
import com.repoguard.agent.entity.ReviewPolicyPromotionEvidence;
import com.repoguard.agent.entity.ReviewRulePolicySnapshot;
import com.repoguard.agent.entity.ReviewStrategyPolicySnapshot;
import com.repoguard.agent.mapper.ReviewPolicyPromotionEvidenceMapper;
import com.repoguard.agent.mapper.projection.ReviewPolicyPromotionEvidenceProjection;
import com.repoguard.agent.review.EnforcementMode;
import com.repoguard.agent.review.ReviewStrategyRelease;
import com.repoguard.agent.review.quality.ReviewQualityGatePolicy;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Component;

@Component
public class ReviewPolicyPromotionEvidenceStore {

    static final String RULE_BASELINE_VERSION = "review-rule-calibration-v1";
    static final String STRATEGY_BASELINE_VERSION = "review-quality-baseline-v1";
    static final String QUALITY_GATE_VERSION = "review-quality-gate-v1";
    static final String SAMPLE_FINGERPRINT_PREFIX = "sha256:aggregate-v1:";
    private static final int MAX_BLOCKERS_LENGTH = 2048;
    private static final int MAX_ACTOR_USERNAME_LENGTH = 255;
    private static final int MAX_TRACE_ID_LENGTH = 128;

    private final ReviewPolicyPromotionEvidenceMapper mapper;
    private final ReviewPolicyAuditContextProvider auditContextProvider;
    private final ReviewQualityGatePolicy qualityGatePolicy;

    public ReviewPolicyPromotionEvidenceStore(
        ReviewPolicyPromotionEvidenceMapper mapper,
        ReviewPolicyAuditContextProvider auditContextProvider,
        ReviewQualityGatePolicy qualityGatePolicy
    ) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
        this.auditContextProvider = Objects.requireNonNull(auditContextProvider, "auditContextProvider");
        this.qualityGatePolicy = Objects.requireNonNull(qualityGatePolicy, "qualityGatePolicy");
    }

    public void recordRulePromotion(
        ReviewRulePolicySnapshot snapshot,
        EnforcementMode sourceMode,
        EnforcementMode targetMode,
        ReviewCalibrationQueueDto evaluation
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.getId() == null) {
            throw new IllegalStateException("Rule policy snapshot id is required before recording promotion evidence");
        }
        ReviewCalibrationQueueDto capturedEvaluation = Objects.requireNonNull(evaluation, "evaluation");
        ReviewCalibrationVersionDto version = Objects.requireNonNull(
            capturedEvaluation.version(),
            "evaluation.version"
        );
        ReviewRuleQualityGateDto qualityGate = Objects.requireNonNull(
            capturedEvaluation.qualityGate(),
            "evaluation.qualityGate"
        );
        ReviewPolicyPromotionEvidenceProjection window = mapper.selectRuleEvidence(
            requireText(version.ruleId(), "version.ruleId"),
            requireText(version.detectorVersion(), "version.detectorVersion"),
            version.ruleConfigVersion(),
            requireText(version.promptVersion(), "version.promptVersion"),
            requireText(version.contextVersion(), "version.contextVersion"),
            requireText(version.schemaVersion(), "version.schemaVersion"),
            requireText(version.verifierVersion(), "version.verifierVersion"),
            requireText(version.aggregationVersion(), "version.aggregationVersion")
        );
        ReviewPolicyPromotionEvidence evidence = evidence(
            RULE_BASELINE_VERSION,
            sourceMode,
            targetMode,
            qualityGate,
            window
        );
        evidence.setTargetType("RULE");
        evidence.setRulePolicySnapshotId(snapshot.getId());
        evidence.setRuleId(requireText(snapshot.getRuleId(), "snapshot.ruleId"));
        mapper.insert(evidence);
    }

    public void recordStrategyPromotion(
        ReviewStrategyPolicySnapshot snapshot,
        ReviewStrategyRelease release,
        EnforcementMode sourceMode,
        EnforcementMode targetMode,
        ReviewRuleQualityGateDto qualityGate
    ) {
        Objects.requireNonNull(snapshot, "snapshot");
        if (snapshot.getId() == null) {
            throw new IllegalStateException("Strategy policy snapshot id is required before recording promotion evidence");
        }
        ReviewStrategyRelease capturedRelease = Objects.requireNonNull(release, "release");
        ReviewPolicyPromotionEvidenceProjection window = mapper.selectStrategyEvidence(
            requireText(capturedRelease.promptVersion(), "release.promptVersion"),
            requireText(capturedRelease.contextVersion(), "release.contextVersion"),
            requireText(capturedRelease.schemaVersion(), "release.schemaVersion"),
            requireText(capturedRelease.verifierVersion(), "release.verifierVersion"),
            requireText(capturedRelease.aggregationVersion(), "release.aggregationVersion")
        );
        ReviewPolicyPromotionEvidence evidence = evidence(
            STRATEGY_BASELINE_VERSION,
            sourceMode,
            targetMode,
            qualityGate,
            window
        );
        evidence.setTargetType("STRATEGY");
        evidence.setStrategyPolicySnapshotId(snapshot.getId());
        mapper.insert(evidence);
    }

    private ReviewPolicyPromotionEvidence evidence(
        String baselineVersion,
        EnforcementMode sourceMode,
        EnforcementMode targetMode,
        ReviewRuleQualityGateDto qualityGate,
        ReviewPolicyPromotionEvidenceProjection projection
    ) {
        ReviewRuleQualityGateDto gate = Objects.requireNonNull(qualityGate, "qualityGate");
        ReviewPolicyPromotionEvidenceProjection window = Objects.requireNonNull(projection, "projection");
        long totalSamples = count(window.totalSamples());
        long labeledSamples = count(window.labeledSamples());
        long totalHighRiskSamples = count(window.totalHighRiskSamples());
        long labeledHighRiskSamples = count(window.labeledHighRiskSamples());
        long confirmedValidSamples = count(window.confirmedValidSamples());
        long falsePositiveSamples = count(window.falsePositiveSamples());
        requireSameCount("labeled samples", gate.labeledSamples(), labeledSamples);
        requireSameCount("labeled high-risk samples", gate.labeledHighRiskSamples(), labeledHighRiskSamples);

        ReviewPolicyAuditContextProvider.AuditContext context = auditContextProvider.current();
        LocalDateTime capturedAt = context == null || context.capturedAt() == null
            ? LocalDateTime.now()
            : context.capturedAt();
        ReviewPolicyPromotionEvidence evidence = new ReviewPolicyPromotionEvidence();
        evidence.setSourceEnforcementMode(Objects.requireNonNull(sourceMode, "sourceMode").name());
        evidence.setTargetEnforcementMode(Objects.requireNonNull(targetMode, "targetMode").name());
        evidence.setQualityBaselineVersion(baselineVersion);
        evidence.setQualityGateVersion(QUALITY_GATE_VERSION);
        evidence.setBaselineCalculatedAt(capturedAt);
        evidence.setSampleCutoffAt(window.sampleCutoffAt() == null ? capturedAt : window.sampleCutoffAt());
        evidence.setTotalSamples(totalSamples);
        evidence.setLabeledSamples(labeledSamples);
        evidence.setTotalHighRiskSamples(totalHighRiskSamples);
        evidence.setLabeledHighRiskSamples(labeledHighRiskSamples);
        evidence.setConfirmedValidSamples(confirmedValidSamples);
        evidence.setFalsePositiveSamples(falsePositiveSamples);
        evidence.setAnchoredSamples(count(window.anchoredSamples()));
        evidence.setDuplicateSamples(count(window.duplicateSamples()));
        evidence.setPrecision(decimal(gate.precision()));
        evidence.setPrecisionWilsonLowerBound(
            qualityGatePolicy.precisionLowerBound(confirmedValidSamples, labeledHighRiskSamples)
        );
        evidence.setFalsePositiveRate(decimal(gate.falsePositiveRate()));
        evidence.setAnchorRate(decimal(gate.anchorRate()));
        evidence.setDuplicateRate(decimal(gate.duplicateRate()));
        evidence.setCommentEligible(gate.commentEligible());
        evidence.setBlockEligible(gate.blockEligible());
        evidence.setQualityStatus(requireText(gate.status(), "qualityGate.status"));
        evidence.setBlockers(truncate(String.join("\n", safeList(gate.blockers())), MAX_BLOCKERS_LENGTH));
        evidence.setSampleFingerprint(
            SAMPLE_FINGERPRINT_PREFIX + requireText(window.sampleFingerprint(), "sampleFingerprint")
        );
        if (context != null) {
            evidence.setActorUserId(context.actorUserId());
            evidence.setActorUsername(truncate(context.actorUsername(), MAX_ACTOR_USERNAME_LENGTH));
            evidence.setTraceId(truncate(context.traceId(), MAX_TRACE_ID_LENGTH));
        }
        evidence.setCreatedAt(capturedAt);
        return evidence;
    }

    private void requireSameCount(String label, long evaluated, long captured) {
        if (evaluated != captured) {
            throw new IllegalStateException(
                "Promotion evidence " + label + " changed during evaluation: evaluated=" + evaluated
                    + ", captured=" + captured
            );
        }
    }

    private long count(Long value) {
        return value == null ? 0L : value;
    }

    private BigDecimal decimal(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }

    private List<String> safeList(List<String> values) {
        return values == null ? List.of() : values;
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    private String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }
}
