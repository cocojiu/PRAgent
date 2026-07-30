package com.repoguard.agent.review.quality;

import java.math.BigDecimal;

public record ReviewQualityGroupBaseline(
    String ruleId,
    String source,
    String repository,
    String language,
    String severity,
    long totalFindings,
    long confirmedValidCount,
    long falsePositiveCount,
    long pendingCount,
    BigDecimal labeledPrecision,
    BigDecimal labeledFalsePositiveRate,
    long anchoredCount,
    BigDecimal anchorRate
) {
}
