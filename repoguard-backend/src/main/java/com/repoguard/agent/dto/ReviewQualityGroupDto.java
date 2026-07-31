package com.repoguard.agent.dto;

import com.repoguard.agent.review.quality.ReviewQualityGroupBaseline;
import java.math.BigDecimal;
import java.util.List;

public record ReviewQualityGroupDto(
    String ruleId,
    String source,
    String repository,
    String language,
    String severity,
    String versionKey,
    String detectorVersion,
    long ruleConfigVersion,
    long policyVersion,
    String promptVersion,
    String contextVersion,
    String schemaVersion,
    String verifierVersion,
    String aggregationVersion,
    long totalFindings,
    long labeledCount,
    BigDecimal labeledCoverage,
    long confirmedValidCount,
    long falsePositiveCount,
    long pendingCount,
    BigDecimal labeledPrecision,
    BigDecimal labeledFalsePositiveRate,
    long highRiskCount,
    BigDecimal highRiskRate,
    long blockingCount,
    BigDecimal blockingRate,
    long revokedBlockingCount,
    long anchoredCount,
    BigDecimal anchorRate,
    long duplicateCount,
    BigDecimal duplicateRate,
    String thresholdStatus,
    List<String> thresholdAlerts
) {

    public static ReviewQualityGroupDto from(ReviewQualityGroupBaseline source) {
        return new ReviewQualityGroupDto(
            source.ruleId(),
            source.source(),
            source.repository(),
            source.language(),
            source.severity(),
            source.versionKey(),
            source.detectorVersion(),
            source.ruleConfigVersion(),
            source.policyVersion(),
            source.promptVersion(),
            source.contextVersion(),
            source.schemaVersion(),
            source.verifierVersion(),
            source.aggregationVersion(),
            source.totalFindings(),
            source.labeledCount(),
            source.labeledCoverage(),
            source.confirmedValidCount(),
            source.falsePositiveCount(),
            source.pendingCount(),
            source.labeledPrecision(),
            source.labeledFalsePositiveRate(),
            source.highRiskCount(),
            source.highRiskRate(),
            source.blockingCount(),
            source.blockingRate(),
            source.revokedBlockingCount(),
            source.anchoredCount(),
            source.anchorRate(),
            source.duplicateCount(),
            source.duplicateRate(),
            source.thresholdStatus(),
            source.thresholdAlerts()
        );
    }
}
