package com.repoguard.agent.mapper.projection;

import java.time.LocalDateTime;

public record ReviewPolicyPromotionEvidenceProjection(
    Long totalSamples,
    Long labeledSamples,
    Long totalHighRiskSamples,
    Long labeledHighRiskSamples,
    Long confirmedValidSamples,
    Long falsePositiveSamples,
    Long anchoredSamples,
    Long duplicateSamples,
    LocalDateTime sampleCutoffAt,
    String sampleFingerprint
) {
}
