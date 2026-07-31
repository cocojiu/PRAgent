package com.repoguard.agent.dto;

import java.util.List;

public record ReviewCalibrationQueueDto(
    ReviewCalibrationVersionDto version,
    long targetLabeledSamples,
    long totalHighRiskFindings,
    long labeledHighRiskSamples,
    long confirmedValidSamples,
    long falsePositiveSamples,
    long pendingHighRiskSamples,
    long remainingToTarget,
    ReviewRuleQualityGateDto qualityGate,
    List<ReviewCalibrationSampleDto> samples
) {
}
