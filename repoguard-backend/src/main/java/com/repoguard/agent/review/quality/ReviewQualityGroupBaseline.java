package com.repoguard.agent.review.quality;

import java.math.BigDecimal;
import java.util.List;

public record ReviewQualityGroupBaseline(
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
}
